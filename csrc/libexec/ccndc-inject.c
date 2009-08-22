/*
 * Watch interests and inject interests wrapped with routing
 * back into the ccnd
 *
 * The new ccndc protocol will need to take the configuration entries
 * and for each unique destination create a FaceInstance.
 * For each prefix, it will issue a ForwardingEntry Action (values?)
 * with the Name (the routed prefix), the FaceID, the ForwardingFlags (what are they?)
 * and a FreshnessSeconds (what are appropriate values?)
 * What is the response to the ForwardingEntry message?
 * 
 * Do we still get the unroutable Interests sent up?
 * What about doing DNS lookups for the dynamic routing?
 * SRV records could be
 * 	_ccnd._udp.parc.com 86400 IN SRV 0 5 4810 ccngateway.parc.com
 * or 	_ccnd._tcp.parc.com 86400 IN SRV 0 5 4810 ccngateway.parc.com
 * (would we prefer TCP over UDP?)
 */

/*
 * parse input lines
 *	ccn:/prefix  udp|tcp hostname|ipaddr port
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netdb.h>

#if defined(NEED_GETADDRINFO_COMPAT)
    #include "getaddrinfo.h"
    #include "dummyin6.h"
#endif
#ifndef AI_ADDRCONFIG
#define AI_ADDRCONFIG 0 /*IEEE Std 1003.1-2001/Cor 1-2002, item XSH/TC1/D6/20*/
#endif

#include <ccn/ccn.h>
#include <ccn/uri.h>

#define DEFAULTPORTSTRING "4485"
#define MAXRIB	1024
struct ribline {
    struct ccn_charbuf *name;
    struct addrinfo *addrinfo;
    struct addrinfo *mcastifaddrinfo;
};

struct routing {
    int nEntries;
    struct ribline rib[MAXRIB];
};

void
ccndc_fatal(int line, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d] line %d: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid(), line);
    vfprintf(stderr, format, ap);
    exit(1);
}

void
ccndc_warn(int line, char *format, ...)
{
    struct timeval t;
    va_list ap;
    va_start(ap, format);

    gettimeofday(&t, NULL);
    fprintf(stderr, "%d.%06d ccndc[%d] line %d: ", (int)t.tv_sec, (unsigned)t.tv_usec, getpid(), line);
    vfprintf(stderr, format, ap);
}

int
ccn_inject_create(struct ccn_charbuf *c,
                      const int sotype,
                      const struct sockaddr *addr,
                      const socklen_t addr_size,
                      const unsigned char *interest,
                      size_t interest_size)
{
    unsigned char *ucp = NULL;
    int res;

    res = ccn_charbuf_append_tt(c, CCN_DTAG_Inject, CCN_DTAG);
    res |= ccn_charbuf_append_tt(c, CCN_DTAG_SOType, CCN_DTAG);
    res |= ccnb_append_number(c, sotype);
    res |= ccn_charbuf_append_closer(c); /* </SOtype> */
    res |= ccn_charbuf_append_tt(c, CCN_DTAG_Address, CCN_DTAG);
    res |= ccn_charbuf_append_tt(c, addr_size, CCN_BLOB);
    ucp = ccn_charbuf_reserve(c, addr_size);
    memcpy(ucp, addr, addr_size);
    c->length += addr_size;
    res |= ccn_charbuf_append_closer(c); /* </Address> */
    ucp = ccn_charbuf_reserve(c, interest_size);
    memcpy(ucp, interest, interest_size);
    c->length += interest_size;
    res |= ccn_charbuf_append_closer(c); /* </Inject> */
    return (res);
}

enum ccn_upcall_res
incoming_interest(
                  struct ccn_closure *selfp,
                  enum ccn_upcall_kind kind,
                  struct ccn_upcall_info *info)
{
    struct routing *rt = selfp->data;
    const unsigned char *ccnb = info->interest_ccnb;
    struct ccn_parsed_interest *pi = info->pi;
    int i;
    int res;

    if (kind == CCN_UPCALL_FINAL)
        return(CCN_UPCALL_RESULT_OK);
    if (kind != CCN_UPCALL_INTEREST || rt == NULL)
        return(CCN_UPCALL_RESULT_ERR);
  
    for (i = 0; i < rt->nEntries; i++) {
        int name = pi->offset[CCN_PI_B_Name];
        int ccnb_size = pi->offset[CCN_PI_E];
        int inlength = pi->offset[CCN_PI_E_Name] - pi->offset[CCN_PI_B_Name];
        int nlength = rt->rib[i].name->length;

        if (inlength >= nlength && 0 == memcmp(rt->rib[i].name->buf, &ccnb[name], nlength - 1)) {
            struct ccn_charbuf *inject = ccn_charbuf_create();
            int socktype = rt->rib[i].addrinfo->ai_socktype;
            socklen_t addr_size = rt->rib[i].addrinfo->ai_addrlen;
            struct sockaddr *addr = rt->rib[i].addrinfo->ai_addr;

            res = ccn_inject_create(inject, socktype, addr, addr_size, ccnb, ccnb_size);
            if (res == 0)
                res = ccn_put(info->h, inject->buf, inject->length);
            if (res != 0) ccndc_warn(__LINE__, "ccn_put failed\n");
            ccn_charbuf_destroy(&inject);
        }
    }
    return(CCN_UPCALL_RESULT_OK);
}

static void
usage(const char *progname)
{
        fprintf(stderr,
                "%s -f configfile\n"
                " Reads configfile and injects routing information "
                "for interest packets that match configured prefixes\n",
                progname);
        exit(1);
}

/*
 * configuration file format
 *
 * <CCN URI> <udp|tcp> <hostname|ipv4 address|ipv6 address> [<port>]
 *
 * anything following "#" is discarded as a comment
 * any host name or address that is resolvable by getaddrinfo is acceptable
 *
 * configuration file format version 2
 *
 * <CCN URI> <udp|tcp> <hostname|ipv4 address|ipv6 address> [<port>] <unicast addr of multiif>
 *
 * In the case where the hostname... parameters resolves to a multicast address it
 * may be necessary to pass the local unicast address of the  interface on which you
 * wish to do the multicast operation.
 */

static int
read_configfile(const char *filename, struct routing *rt)
{
    int res;
    char buf[256], strtokbuf[256];
    int configerrors = 0;
    int configlinenumber = 0;
    FILE *cfg;
    struct ccn_charbuf *name = NULL;
    int socktype = 0;
    char *rhostname;
    char *rhostportstring;
    int rhostport;
    char *mcastifaddr;
    struct addrinfo hints = {.ai_family = AF_UNSPEC, .ai_flags = AI_ADDRCONFIG};
    struct addrinfo mcasthints = {.ai_family = AF_UNSPEC, .ai_flags = (AI_ADDRCONFIG|AI_NUMERICHOST)};
    struct addrinfo *raddrinfo = NULL;
    struct addrinfo *mcastifaddrinfo = NULL;
    const char *seps = " \t\n";
    char *cp = NULL;
    char *last = NULL;

    cfg = fopen(filename, "r");
    if (cfg == NULL)
        ccndc_fatal(__LINE__, "%s (%s)\n", strerror(errno), filename);

    while (fgets((char *)buf, sizeof(buf), cfg) && (rt->nEntries < MAXRIB)) {
        int len;
        len = strlen(buf);
        configlinenumber++;
        if (buf[0] == '#' || len == 0)
            continue;
        if (buf[len - 1] == '\n')
            buf[len - 1] = '\0';
        memcpy(strtokbuf, buf, sizeof(buf));
        cp = index(strtokbuf, '#');
        if (cp != NULL)
            *cp = '\0';
        cp = strtok_r(strtokbuf, seps, &last);
        if (cp == NULL)
            continue;

        name = ccn_charbuf_create();
        res = ccn_name_from_uri(name, cp);
        if (res < 0) {
            ccndc_warn(__LINE__, "config file error (line %d), bad CCN URI '%s'\n", configlinenumber, cp);
            configerrors--;
            continue;
        }
        cp = strtok_r(NULL, seps, &last);
        if (cp == NULL) {
            ccndc_warn(__LINE__, "config file error (line %d), missing address type in %s\n", configlinenumber,  buf);
            configerrors--;
            continue;
        }
        if (strcmp(cp, "udp") == 0)
            socktype = SOCK_DGRAM;
        else if (strcmp(cp, "tcp") == 0)
            socktype = SOCK_STREAM;
        else {
            ccndc_warn(__LINE__, "config file error (line %d), unrecognized address type '%s'\n", configlinenumber, cp);
            configerrors--;
            continue;
        }
        rhostname = strtok_r(NULL, seps, &last);
        if (rhostname == NULL) {
            ccndc_warn(__LINE__, "config file error (line %d), missing hostname in %s\n", configlinenumber, buf);
            configerrors--;
            continue;
        }
        rhostportstring = strtok_r(NULL, seps, &last);
        if (rhostportstring == NULL) rhostportstring = DEFAULTPORTSTRING;
        rhostport = atoi(rhostportstring);
        if (rhostport <= 0 || rhostport > 65535) {
            ccndc_warn(__LINE__, "config file error (line %d), invalid port %s\n", configlinenumber, rhostportstring);
            configerrors--;
            continue;
        }
        hints.ai_socktype = socktype;
        res = getaddrinfo(rhostname, rhostportstring, &hints, &raddrinfo);
        if (res != 0 || raddrinfo == NULL) {
            ccndc_warn(__LINE__, "config file error (line %d), getaddrinfo: %s\n", configlinenumber, gai_strerror(res));
            configerrors--;
            continue;
        }

        mcastifaddr = strtok_r(NULL, seps, &last);
        if (mcastifaddr != NULL) {
            res = getaddrinfo(mcastifaddr, NULL, &mcasthints, &mcastifaddrinfo);
            if (res != 0) {
                ccndc_warn(__LINE__, "config file error (line %d), getaddrinfo: %s\n", configlinenumber, gai_strerror(res));
                configerrors--;
                continue;
            }
        }
        
        /* we have successfully read a config file line */
        rt->rib[rt->nEntries].name = name;
        rt->rib[rt->nEntries].addrinfo = raddrinfo;
        rt->rib[rt->nEntries].mcastifaddrinfo = mcastifaddrinfo;
        rt->nEntries++;
        
    }
    fclose(cfg);
    return (configerrors);
}
int
main(int argc, char **argv)
{
    const char *progname = argv[0];
    const char *configfile = NULL;
    int test = 0;
    struct ccn *ccn = NULL;
    int res;
    struct routing rt = { 0 };
    struct ccn_closure in_interest = {.p=&incoming_interest, .data=&rt};
    struct ccn_charbuf *namebuf = NULL;

    while ((res = getopt(argc, argv, "f:ht")) != -1) {
        switch (res) {
            case 'f':
                configfile = optarg;
                break;
            case 't':
                test = 1;
                break;
            default:
            case 'h':
                usage(progname);
                break;
        }
    }

    if (configfile == NULL) {
        usage(progname);
    }

    res = read_configfile(configfile, &rt);
    if (res < 0)
        ccndc_fatal(__LINE__, "Error(s) in configuration file\n");

    if (test) {
        exit(0);
    }

    ccn = ccn_create();
    if (ccn_connect(ccn, NULL) == -1)
        ccndc_fatal(__LINE__, "%s connecting to ccnd\n", strerror(errno));
    
    /* Set up a handler for interests */
    namebuf = ccn_charbuf_create();
    ccn_name_init(namebuf);
    ccn_set_interest_filter(ccn, namebuf, &in_interest);
    ccn_charbuf_destroy(&namebuf);
    
    ccn_run(ccn, -1);
    exit(0);
}