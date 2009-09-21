/**
 * @file ccndc.c
 * @brief Bring up a link to another ccnd.
 *
 * A CCNx program.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <limits.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netdb.h>
#include <netinet/in.h>
#define BIND_8_COMPAT
#include <arpa/nameser.h>
#include <resolv.h>
#include <errno.h>
#include <ccn/bloom.h>
#include <ccn/ccn.h>
#include <ccn/ccnd.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>
#include <ccn/face_mgmt.h>
#include <ccn/reg_mgmt.h>
#include <ccn/sockcreate.h>
#include <ccn/signing.h>
#include <ccn/keystore.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
    #include "dummyin6.h"
#endif

#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

#ifndef NS_MAXMSG
#define NS_MAXMSG 65535
#endif

#ifndef NS_MAXDNAME
#ifdef MAXDNAME
#define NS_MAXDNAME MAXDNAME
#endif
#endif

#ifndef T_SRV
#define T_SRV 33
#endif

/*
 * private types
 */
struct prefix_face_list_item {
    struct ccn_charbuf *prefix;
    struct ccn_face_instance *fi;
    int flags;
    struct prefix_face_list_item *next;
};

/*
 * global constant (but not staticly initializable) data
 */
static struct ccn_charbuf *local_scope_template = NULL;
static struct ccn_charbuf *no_name = NULL;
static unsigned char ccndid_storage[32] = {0};
static const unsigned char *ccndid = ccndid_storage;
static size_t ccndid_size;
/* static struct __res_state *state = NULL; */

static void
usage(const char *progname)
{
    fprintf(stderr,
            "%s [-d] (-f configfile | (add|del) uri proto host [port [flags [mcastttl [mcastif]]]])\n"
            "   -d enter dynamic mode and create FIB entries based on DNS SRV records\n"
            "   -f configfile add or delete FIB entries based on contents of configfile\n"
            "	add|del add or delete FIB entry based on parameters\n",
            progname);
    exit(1);
}

void
ccndc_warn(int lineno, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d]:%d: ", (int)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), lineno);
    vfprintf(stderr, format, ap);
}

void
ccndc_fatal(int line, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d]:%d: ", (int)t.tv_sec, (unsigned)t.tv_usec, (int)getpid(), line);
    vfprintf(stderr, format, ap);
    exit(1);
}

#define ON_ERROR_EXIT(resval) on_error_exit((resval), __LINE__)

static void
on_error_exit(int res, int lineno)
{
    if (res >= 0)
        return;
    ccndc_fatal(lineno, "fatal error, res = %d\n", res);
}

#define ON_ERROR_CLEANUP(resval) \
	{ 			\
            if ((resval) < 0) \
                                   goto Cleanup; \
        }

static void
initialize_global_data(void) {
    /* Set up an Interest template to indicate scope 1 (Local) */
    local_scope_template = ccn_charbuf_create();
    if (local_scope_template == NULL) {
        ON_ERROR_EXIT(-1);
    }

    ON_ERROR_EXIT(ccn_charbuf_append_tt(local_scope_template, CCN_DTAG_Interest, CCN_DTAG));
    ON_ERROR_EXIT(ccn_charbuf_append_tt(local_scope_template, CCN_DTAG_Name, CCN_DTAG));
    ON_ERROR_EXIT(ccn_charbuf_append_closer(local_scope_template));	/* </Name> */
    ON_ERROR_EXIT(ccnb_tagged_putf(local_scope_template, CCN_DTAG_Scope, "1"));
    ON_ERROR_EXIT(ccn_charbuf_append_closer(local_scope_template));	/* </Interest> */

    /* Create a null name */
    no_name = ccn_charbuf_create();
    if (no_name == NULL) {
        ON_ERROR_EXIT(-1);
    }
    ON_ERROR_EXIT(ccn_name_init(no_name));

#if 0
    /* allocate a resolver library state */
    state = calloc(1, sizeof(*state));
    if (state == NULL) {
        ON_ERROR_EXIT(-1);
    }
#endif
}

/*
 * this should eventually be used as the basis for a library function
 *    ccn_get_ccndid(...)
 * which would retrieve a copy of the ccndid from the
 * handle, where it should be cached.
 */
static int
get_ccndid(struct ccn *h, const unsigned char *ccndid, size_t ccndid_storage_size)
{

    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    char ping_uri[] = "ccnx:/ccnx/ping";
    const unsigned char *ccndid_result;
    static size_t ccndid_result_size;
    int res;

    name = ccn_charbuf_create();
    if (name == NULL) {
        ON_ERROR_EXIT(-1);
    }

    resultbuf = ccn_charbuf_create();
    if (resultbuf == NULL) {
        ON_ERROR_EXIT(-1);
    }


    ON_ERROR_EXIT(ccn_name_from_uri(name, ping_uri));
    ON_ERROR_EXIT(ccn_name_append_numeric(name, CCN_MARKER_NONE, getpid()));
    ON_ERROR_EXIT(ccn_get(h, name, local_scope_template, 200, resultbuf, &pcobuf, NULL, 0));
    res = ccn_ref_tagged_BLOB(CCN_DTAG_PublisherPublicKeyDigest,
                              resultbuf->buf,
                              pcobuf.offset[CCN_PCO_B_PublisherPublicKeyDigest],
                              pcobuf.offset[CCN_PCO_E_PublisherPublicKeyDigest],
                              &ccndid_result, &ccndid_result_size);
    ON_ERROR_EXIT(res);
    if (ccndid_result_size > ccndid_storage_size)
        ON_ERROR_EXIT(-1);
    memcpy((void *)ccndid, ccndid_result, ccndid_result_size);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&resultbuf);
    return (ccndid_result_size);
}

static struct prefix_face_list_item *prefix_face_list_item_create(void)
{
    struct prefix_face_list_item *pfl = calloc(1, sizeof(struct prefix_face_list_item));
    struct ccn_face_instance *fi = calloc(1, sizeof(*fi));
    struct ccn_charbuf *store = ccn_charbuf_create();

    if (pfl == NULL || fi == NULL || store == NULL) {
        if (pfl) free(pfl);
        if (fi) ccn_face_instance_destroy(&fi);
        if (store) ccn_charbuf_destroy(&store);
    }
    pfl->fi = fi;
    pfl->fi->store = store;
    return (pfl);
}

static void prefix_face_list_destroy(struct prefix_face_list_item **pflpp)
{
    struct prefix_face_list_item *pflp = *pflpp;
    struct prefix_face_list_item *next;

    if (pflp == NULL) return;
    while (pflp) {
        ccn_face_instance_destroy(&pflp->fi);
        ccn_charbuf_destroy(&pflp->prefix);
        next = pflp->next;
        free(pflp);
        pflp = next;
    }
    *pflpp = NULL;
}

/**
 *  @brief Register an interest prefix as being routed to a given face
 *  @param h  the ccnd handle
 *  @param keystore  a ccn keystore containing the keys used to authenticate this operation
 *  @param name_prefix  the prefix to be registered
 *  @param face_instance  the face to which the interests with the prefix should be routed
 *  @param flags
 *  @result returns (positive) faceid on success, -1 on error
 */
static int
register_prefix(struct ccn *h, struct ccn_keystore *keystore, struct ccn_charbuf *name_prefix, struct ccn_face_instance *face_instance, int flags)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *resultbuf = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *newface = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *prefixreg = NULL;
    struct ccn_parsed_ContentObject pcobuf = {0};
    struct ccn_face_instance *new_face_instance = NULL;
    struct ccn_forwarding_entry forwarding_entry_storage = {0};
    struct ccn_forwarding_entry *forwarding_entry = &forwarding_entry_storage;
    const unsigned char *ptr = NULL;
    size_t length = 0;
    long expire = -1;
    int res;

    /* Encode the given face instance */
    newface = ccn_charbuf_create();
    if (newface == NULL) {
        ON_ERROR_CLEANUP(-1);
    }
    ON_ERROR_CLEANUP(ccnb_append_face_instance(newface, face_instance));

    /* Construct a key locator containing the key itself */
    keylocator = ccn_charbuf_create();
    ON_ERROR_CLEANUP(ccn_charbuf_append_tt(keylocator, CCN_DTAG_KeyLocator, CCN_DTAG));
    ON_ERROR_CLEANUP(ccn_charbuf_append_tt(keylocator, CCN_DTAG_Key, CCN_DTAG));
    ON_ERROR_CLEANUP(ccn_append_pubkey_blob(keylocator, ccn_keystore_public_key(keystore)));
    ON_ERROR_CLEANUP(ccn_charbuf_append_closer(keylocator));	/* </Key> */
    ON_ERROR_CLEANUP(ccn_charbuf_append_closer(keylocator));	/* </KeyLocator> */
    signed_info = ccn_charbuf_create();
    res = ccn_signed_info_create(signed_info,
                                 /* pubkeyid */ ccn_keystore_public_key_digest(keystore),
                                 /* publisher_key_id_size */ ccn_keystore_public_key_digest_length(keystore),
                                 /* datetime */ NULL,
                                 /* type */ CCN_CONTENT_DATA,
                                 /* freshness */ expire,
                                 /* finalblockid */ NULL,
                                 keylocator);
    ON_ERROR_CLEANUP(res);

    temp = ccn_charbuf_create();
    if (temp == NULL) {
        ON_ERROR_CLEANUP(-1);
    }
    res = ccn_encode_ContentObject(temp,
                                   no_name,
                                   signed_info,
                                   newface->buf,
                                   newface->length,
                                   NULL,
                                   ccn_keystore_private_key(keystore));
    ON_ERROR_CLEANUP(res);
    
    resultbuf = ccn_charbuf_create();
    if (resultbuf == NULL) {
        ON_ERROR_CLEANUP(-1);
    }

    /* Construct the Interest name that will create the new face */
    name = ccn_charbuf_create();
    if (name == NULL) {
        ON_ERROR_CLEANUP(-1);
    }
    ON_ERROR_CLEANUP(ccn_name_init(name));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "ccnx"));
    ON_ERROR_CLEANUP(ccn_name_append(name, face_instance->ccnd_id, face_instance->ccnd_id_size));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "newface"));
    ON_ERROR_CLEANUP(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
    ON_ERROR_CLEANUP(res);

    ON_ERROR_CLEANUP(ccn_content_get_value(resultbuf->buf, resultbuf->length, &pcobuf, &ptr, &length));
    new_face_instance = ccn_face_instance_parse(ptr, length);
    if (new_face_instance == NULL)
        ON_ERROR_CLEANUP(-1);
    ON_ERROR_CLEANUP(new_face_instance->faceid);
    
    /* Finally, register the prefix */
    forwarding_entry->action = "prefixreg";
    forwarding_entry->name_prefix = name_prefix;
    forwarding_entry->ccnd_id = face_instance->ccnd_id;
    forwarding_entry->ccnd_id_size = face_instance->ccnd_id_size;
    forwarding_entry->faceid = new_face_instance->faceid;
    forwarding_entry->flags = flags;
    forwarding_entry->lifetime = (~0U) >> 1;

    prefixreg = ccn_charbuf_create();
    if (prefixreg == NULL) {
        ON_ERROR_CLEANUP(-1);
    }
    ON_ERROR_CLEANUP(ccnb_append_forwarding_entry(prefixreg, forwarding_entry));
    temp->length = 0;
    res = ccn_encode_ContentObject(temp,
                                   no_name,
                                   signed_info,
                                   prefixreg->buf,
                                   prefixreg->length,
                                   NULL,
                                   ccn_keystore_private_key(keystore));
    ON_ERROR_CLEANUP(res);
    ON_ERROR_CLEANUP(ccn_name_init(name));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "ccnx"));
    ON_ERROR_CLEANUP(ccn_name_append(name, face_instance->ccnd_id, face_instance->ccnd_id_size));
    ON_ERROR_CLEANUP(ccn_name_append_str(name, "prefixreg"));
    ON_ERROR_CLEANUP(ccn_name_append(name, temp->buf, temp->length));
    res = ccn_get(h, name, local_scope_template, 1000, resultbuf, &pcobuf, NULL, 0);
    ON_ERROR_CLEANUP(res);

    res = new_face_instance->faceid;

    ccn_charbuf_destroy(&newface);
    ccn_charbuf_destroy(&keylocator);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    ccn_face_instance_destroy(&new_face_instance);
    ccn_charbuf_destroy(&prefixreg);
    
    return (res);

    /* This is where ON_ERROR_CLEANUP sends us in case of an error
     * and we must free any storage we allocated before returning.
     */
 Cleanup:
    ccn_charbuf_destroy(&newface);
    ccn_charbuf_destroy(&keylocator);
    ccn_charbuf_destroy(&signed_info);
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&resultbuf);
    ccn_charbuf_destroy(&name);
    ccn_face_instance_destroy(&new_face_instance);
    ccn_charbuf_destroy(&prefixreg);

    return (-1);
}

static void
fill_prefix_face_list_item(struct prefix_face_list_item *pflp,
                           struct ccn_charbuf *prefix,
                           int ipproto,
                           int mcast_ttl,
                           char *host,
                           char *port,
                           char *mcastif,
                           int lifetime,
                           int flags)
{
    ssize_t host_offset, port_offset;
    ssize_t mcastif_offset;
    struct ccn_charbuf *store = NULL;

    pflp->prefix = prefix;
    pflp->fi->action = "newface";
    pflp->fi->descr.ipproto = ipproto;
    pflp->fi->descr.mcast_ttl = mcast_ttl;
        
    store = pflp->fi->store;
    host_offset = store->length;
    ccn_charbuf_append_string(store, host);
    ccn_charbuf_append_value(store, 0, 1);
    port_offset = store->length;
    ccn_charbuf_append_string(store, port);
    ccn_charbuf_append_value(store, 0, 1);
    if (mcastif != NULL) {
        mcastif_offset = store->length;
        ccn_charbuf_append_string(store, mcastif);
        ccn_charbuf_append_value(store, 0, 1);
    } else {
        mcastif_offset = -1;
    }
    pflp->fi->descr.address = (char *)store->buf + host_offset;
    pflp->fi->descr.port = (char *)store->buf + port_offset;
    pflp->fi->descr.source_address = (mcastif_offset == -1) ? NULL : (char *)store->buf + mcastif_offset;
    pflp->fi->lifetime = lifetime;
    pflp->flags = flags;
}
static int
process_command_tokens(struct prefix_face_list_item *pfltail,
                       int lineno,
                       char *cmd,
                       char *uri,
                       char *proto,
                       char *host,
                       char *port,
                       char *flags,
                       char *mcastttl,
                       char *mcastif)
{
    int lifetime;
    struct ccn_charbuf *prefix;
    int ipproto;
    int socktype;
    int iflags;
    int imcastttl;
    char rhostnamebuf[NI_MAXHOST];
    char rhostportbuf[NI_MAXSERV];
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG)};
    struct addrinfo mcasthints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG | AI_NUMERICHOST)};
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *mcastifaddrinfo = NULL;
    struct prefix_face_list_item *pflp;
    int res;

    if (cmd == NULL) {
        ccndc_warn(__LINE__, "command error (line %d), missing command\n", lineno);
        return (-1);
    }   
    if (strcasecmp(cmd, "add") == 0)
        lifetime = (~0U) >> 1;
    else if (strcasecmp(cmd, "del") == 0)
        lifetime = 0;
    else {
        ccndc_warn(__LINE__, "command error (line %d), unrecognized command '%s'\n", lineno, cmd);
        return (-1);
    }

    if (uri == NULL) {
        ccndc_warn(__LINE__, "command error (line %d), missing CCNx URI\n", lineno);
        return (-1);
    }   
    prefix = ccn_charbuf_create();
    res = ccn_name_from_uri(prefix, uri);
    if (res < 0) {
        ccndc_warn(__LINE__, "command error (line %d), bad CCNx URI '%s'\n", lineno, uri);
        return (-1);
    }

    if (proto == NULL) {
        ccndc_warn(__LINE__, "command error (line %d), missing address type\n", lineno);
        return (-1);
    }
    if (strcasecmp(proto, "udp") == 0) {
        ipproto = IPPROTO_UDP;
        socktype = SOCK_DGRAM;
    }
    else if (strcasecmp(proto, "tcp") == 0) {
        ipproto = IPPROTO_TCP;
        socktype = SOCK_STREAM;
    }
    else {
        ccndc_warn(__LINE__, "command error (line %d), unrecognized address type '%s'\n", lineno, proto);
        return (-1);
    }

    if (host == NULL) {
        ccndc_warn(__LINE__, "command error (line %d), missing hostname\n", lineno);
        return (-1);
    }

    if (port == NULL) port = CCN_DEFAULT_UNICAST_PORT;

    hints.ai_socktype = socktype;
    res = getaddrinfo(host, port, &hints, &raddrinfo);
    if (res != 0 || raddrinfo == NULL) {
        ccndc_warn(__LINE__, "command error (line %d), getaddrinfo: %s\n", lineno, gai_strerror(res));
        return (-1);
    }
    res = getnameinfo(raddrinfo->ai_addr, raddrinfo->ai_addrlen,
                      rhostnamebuf, sizeof(rhostnamebuf),
                      rhostportbuf, sizeof(rhostportbuf),
                      NI_NUMERICHOST | NI_NUMERICSERV);
    freeaddrinfo(raddrinfo);
    if (res != 0) {
        ccndc_warn(__LINE__, "command error (line %d), getnameinfo: %s\n", lineno, gai_strerror(res));
        return (-1);
    }

    iflags = 0;
    if (flags != NULL) {
        iflags = atoi(flags);
        if ((iflags & ~(CCN_FORW_ACTIVE | CCN_FORW_CHILD_INHERIT | CCN_FORW_ADVERTISE)) != 0) {
            ccndc_warn(__LINE__, "command error (line %d), invalid flags 0x%x\n", lineno, iflags);
            return (-1);
        }
    }

    imcastttl = -1;
    if (mcastttl != NULL) {
        imcastttl = atoi(mcastttl);
        if (imcastttl < 0 || imcastttl > 255) {
            ccndc_warn(__LINE__, "command error (line %d), invalid multicast ttl: %s\n", lineno, mcastttl);
            return (-1);
        }
    }

    if (mcastif != NULL) {
        res = getaddrinfo(mcastif, NULL, &mcasthints, &mcastifaddrinfo);
        if (res != 0) {
            ccndc_warn(__LINE__, "command error (line %d), mcastifaddr getaddrinfo: %s\n", lineno, gai_strerror(res));
            return (-1);
        }
    }

    /* we have successfully parsed a command line */
    pflp = prefix_face_list_item_create();
    if (pflp == NULL) {
        ccndc_fatal(__LINE__, "Unable to allocate prefix_face_list_item\n");
    }
    fill_prefix_face_list_item(pflp, prefix, ipproto, imcastttl, rhostnamebuf, rhostportbuf, mcastif, lifetime, iflags);
    pfltail->next = pflp;
    pfltail = pflp;
    return (0);
}

static int
read_configfile(const char *filename, struct prefix_face_list_item *pfltail)
{
    int configerrors = 0;
    int lineno = 0;
    char *cmd;
    char *uri;
    char *proto;
    char *host;
    char *port;
    char *flags;
    char *mcastttl;
    char *mcastif;
    FILE *cfg;
    char buf[1024];
    const char *seps = " \t\n";
    char *cp = NULL;
    char *last = NULL;
    int res;

    cfg = fopen(filename, "r");
    if (cfg == NULL)
        ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);

    while (fgets((char *)buf, sizeof(buf), cfg)) {
        int len;
        lineno++;
        len = strlen(buf);
        if (buf[0] == '#' || len == 0)
            continue;
        if (buf[len - 1] == '\n')
            buf[len - 1] = '\0';
        cp = index(buf, '#');
        if (cp != NULL)
            *cp = '\0';

        cmd = strtok_r(buf, seps, &last);
        uri = strtok_r(NULL, seps, &last);
        proto = strtok_r(NULL, seps, &last);
        host = strtok_r(NULL, seps, &last);
        port = strtok_r(NULL, seps, &last);
        flags = strtok_r(NULL, seps, &last);
        mcastttl = strtok_r(NULL, seps, &last);
        mcastif = strtok_r(NULL, seps, &last);
        res = process_command_tokens(pfltail, lineno, cmd, uri, proto, host, port, flags, mcastttl, mcastif);
        if (res < 0) configerrors--;
    }
    fclose(cfg);
    return (configerrors);
}

enum ccn_upcall_res
incoming_interest(
                  struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    const unsigned char *ccnb = info->interest_ccnb;
    struct ccn_indexbuf *comps = info->interest_comps;
    struct ccn_keystore *keystore = selfp->data;
    const unsigned char *comp0 = NULL;
    size_t comp0_size = 0;
    char *proto = NULL;
    union {
        HEADER header;
        unsigned char buf[NS_MAXMSG];
    } ans;
    ssize_t ans_size;
    char srv_name[NS_MAXDNAME];
    int qdcount, ancount, i;
    unsigned char *msg, *msgend;
    unsigned char *end;
    int type, class, ttl, size, priority, weight, port, minpriority;
    char host[NS_MAXDNAME];
    char portstring[10];
    struct prefix_face_list_item *pflhead = prefix_face_list_item_create();
    struct prefix_face_list_item *pfl;
    int res;

    if (kind == CCN_UPCALL_FINAL)
        return (CCN_UPCALL_RESULT_OK);
    if (kind != CCN_UPCALL_INTEREST)
        return (CCN_UPCALL_RESULT_ERR);
    if (comps->n < 1)
        return (CCN_UPCALL_RESULT_OK);
  
    port = 0;
    host[0] = 0;
    
    res = ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb, comps->buf[0], comps->buf[1],
                              &comp0, &comp0_size);
    if (res < 0 || comp0_size > (NS_MAXDNAME - 12))
        return (CCN_UPCALL_RESULT_OK);
    if (memchr(comp0, '.', comp0_size) == NULL)
        return (CCN_UPCALL_RESULT_OK);

#if 0
    if (! (state->options & RES_INIT)) res_ninit(state);
#else
    res_init();
#endif
    /* Step 1: construct the SRV record name, and see if there's a ccn service gateway.
     * 	       Prefer TCP service over UDP, though this might change.
     */

    proto = "tcp";
    sprintf(srv_name, "_ccnx._tcp.%.*s", (int)comp0_size, comp0);
#if 0
    ans_size = res_nquery(state, srv_name, C_IN, T_SRV, ans.buf, sizeof(ans.buf));
#else
    ans_size = res_query(srv_name, C_IN, T_SRV, ans.buf, sizeof(ans.buf));
#endif
    if (ans_size < 0) {
        proto = "udp";
        sprintf(srv_name, "_ccnx._udp.%.*s", (int)comp0_size, comp0);
#if 0
        ans_size = res_nquery(state, srv_name, C_IN, T_SRV, ans.buf, sizeof(ans.buf));
#else
        ans_size = res_query(srv_name, C_IN, T_SRV, ans.buf, sizeof(ans.buf));
#endif
        if (ans_size < 0)
            return (CCN_UPCALL_RESULT_ERR);
    }
    if (ans_size > sizeof(ans.buf))
        return (CCN_UPCALL_RESULT_ERR);
    
    /* Step 2: skip over the header and question sections */
    qdcount = ntohs(ans.header.qdcount);
    ancount = ntohs(ans.header.ancount);
    msg = ans.buf + sizeof(ans.header);
    msgend = ans.buf + ans_size;

    for (i = qdcount; i > 0; --i) {
        if ((size = dn_skipname(msg, msgend)) < 0)
            return (CCN_UPCALL_RESULT_ERR);
        msg = msg + size + QFIXEDSZ;
    }
    /* Step 3: process the answer section */
    
    minpriority = INT_MAX;
    for (i = ancount; i > 0; --i) {
  	size = dn_expand(ans.buf, msgend, msg, srv_name, sizeof (srv_name));
  	if (size < 0) 
  	    return (CCN_UPCALL_RESULT_ERR);
  	msg = msg + size;
  	GETSHORT(type, msg);
  	GETSHORT(class, msg);
  	GETLONG(ttl, msg);
  	GETSHORT(size, msg);
  	if ((end = msg + size) > msgend)
            return (CCN_UPCALL_RESULT_ERR);

  	if (type != T_SRV) {
            msg = end;
            continue;
  	}

        /* if the priority is numerically lower (more desirable) then remember
         * everything -- note that priority is destroyed, but we don't use it
         * when we register a prefix so it doesn't matter -- only the host
         * and port are necessary.
         */
  	GETSHORT(priority, msg);
        if (priority < minpriority) {
            minpriority = priority;
            GETSHORT(weight, msg);
            GETSHORT(port, msg);
            size = dn_expand(ans.buf, msgend, msg, host, sizeof (host));
            if (size < 0)
                return (CCN_UPCALL_RESULT_ERR);
        }
  	msg = end;
    }
 
    /* now process the results */
    /* pflhead, lineno=0, "add" "ccnx:/asdfasdf.com/" "tcp|udp", host, portstring, NULL NULL NULL */
    sprintf(srv_name, "ccnx:/%.*s", (int)comp0_size, comp0);
    sprintf(portstring, "%d", port);
    res = process_command_tokens(pflhead, 0,
                                 "add",
                                 srv_name,
                                 proto,
                                 host,
                                 portstring,
                                 NULL, NULL, NULL);
    if (res < 0)
        return (CCN_UPCALL_RESULT_ERR);

    for (pfl = pflhead->next; pfl != NULL; pfl = pfl->next) {
        pfl->fi->ccnd_id = ccndid;
        pfl->fi->ccnd_id_size = ccndid_size;
        res = register_prefix(info->h, keystore, pfl->prefix, pfl->fi, pfl->flags);
        if (res < 0) {
            ccndc_warn(__LINE__, "Unable to register prefix %s\n", pfl->prefix);
        }
    }
    prefix_face_list_destroy(&pflhead);
    return(CCN_UPCALL_RESULT_OK);
}


int
main(int argc, char **argv)
{
    struct ccn *h = NULL;
    struct ccn_charbuf *temp = NULL;
    const char *progname = NULL;
    const char *configfile = NULL;
    struct prefix_face_list_item *pflhead = prefix_face_list_item_create();
    struct prefix_face_list_item *pfl;
    struct ccn_keystore *keystore = NULL;
    int dynamic = 0;
    struct ccn_closure interest_closure = {.p=&incoming_interest};
    int res;
    char ch;
    
    initialize_global_data();

    progname = argv[0];
    while ((ch = getopt(argc, argv, "hf:d")) != -1) {
        switch (ch) {
        case 'f':
            configfile = optarg;
            break;
        case 'd':
            dynamic = 1;
            break;
        case 'h':
        default:
            usage(progname);
        }
    }

    if (optind < argc) {
        /* config file cannot be combined with command line */
        if (configfile != NULL) {
            usage(progname);
        }
        /* (add|delete) uri type host [port [flags [mcast-ttl [mcast-if]]]] */

        if (argc - optind < 4 || argc - optind > 7)
            usage(progname);

        res = process_command_tokens(pflhead, 0,
                                     argv[optind],
                                     argv[optind+1],
                                     argv[optind+2],
                                     argv[optind+3],
                                     (optind + 4) < argc ? argv[optind+4] : NULL,
                                     (optind + 5) < argc ? argv[optind+5] : NULL,
                                     (optind + 6) < argc ? argv[optind+6] : NULL,
                                     (optind + 7) < argc ? argv[optind+7] : NULL);
        if (res < 0)
            usage(progname);
    }

    if (configfile) {
        read_configfile(configfile, pflhead);
    }

    h = ccn_create();
    res = ccn_connect(h, NULL);
    if (res < 0) {
        ccn_perror(h, "ccn_connect");
        exit(1);
    }



    temp = ccn_charbuf_create();
    keystore = ccn_keystore_create();
    ON_ERROR_EXIT(ccn_charbuf_putf(temp, "%s/.ccnx/.ccnx_keystore", getenv("HOME")));
    res = ccn_keystore_init(keystore,
                            ccn_charbuf_as_string(temp),
                            "Th1s1sn0t8g00dp8ssw0rd.");
    ON_ERROR_EXIT(res);

    ccndid_size = get_ccndid(h, ccndid, sizeof(ccndid_storage));
    for (pfl = pflhead->next; pfl != NULL; pfl = pfl->next) {
        pfl->fi->ccnd_id = ccndid;
        pfl->fi->ccnd_id_size = ccndid_size;
        res = register_prefix(h, keystore, pfl->prefix, pfl->fi, pfl->flags);
        if (res < 0) {
            ccndc_warn(__LINE__, "Unable to register prefix %s\n", pfl->prefix);
        }
    }
    prefix_face_list_destroy(&pflhead);
    if (dynamic) {
        /* Set up a handler for interests */
        interest_closure.data = keystore;
        ccn_name_init(temp);
        ccn_set_interest_filter(h, temp, &interest_closure);
        ccn_charbuf_destroy(&temp);
        ccn_run(h, -1);
    }
    ccn_destroy(&h);
    exit(res < 0);
}
