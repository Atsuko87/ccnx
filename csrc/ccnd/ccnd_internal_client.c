/**
 * @file ccnd_internal_client.c
 *
 * Internal client of ccnd, handles requests for
 * inspecting and controlling operation of the ccnd;
 * requests and responses themselves use ccn protocols.
 *
 * Part of ccnd - the CCNx Daemon.
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

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/ccn_private.h>
#include <ccn/keystore.h>
#include <ccn/schedule.h>
#include <ccn/signing.h>
#include <ccn/uri.h>
#include "ccnd_private.h"

/**
 * Local interpretation of selfp->intdata
 */
#define MORECOMPS_MASK 0x00FF
#define OPER_MASK      0xFF00
#define OP_PING        0x0000
#define OP_REG_SELF    0x0100
#define OP_NEWFACE     0x0200
#define OP_PREFIXREG   0x0300
/**
 * Common interest handler for ccnd_internal_client
 */
static enum ccn_upcall_res
ccnd_answer_req(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    struct ccn_charbuf *msg = NULL;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *keylocator = NULL;
    struct ccn_charbuf *signed_info = NULL;
    struct ccn_charbuf *reply_body = NULL;
    struct ccnd_handle *ccnd = NULL;
    struct ccn_keystore *keystore = NULL;
    int res = 0;
    int start = 0;
    int end = 0;
    int morecomps = 0;
    const unsigned char *final_comp = NULL;
    size_t final_size = 0;
    int freshness = 10;
    
    switch (kind) {
        case CCN_UPCALL_FINAL:
            free(selfp);
            return(CCN_UPCALL_RESULT_OK);
        case CCN_UPCALL_INTEREST:
            break;
        case CCN_UPCALL_CONSUMED_INTEREST:
            return(CCN_UPCALL_RESULT_OK);
        default:
            return(CCN_UPCALL_RESULT_ERR);
    }
    ccnd = (struct ccnd_handle *)selfp->data;
    if ((ccnd->debug & 128) != 0)
        ccnd_debug_ccnb(ccnd, __LINE__, "ccnd_answer_req", NULL,
                        info->interest_ccnb, info->pi->offset[CCN_PI_E]);
    morecomps = selfp->intdata & MORECOMPS_MASK;
    if ((info->pi->answerfrom & CCN_AOK_NEW) == 0)
        return(CCN_UPCALL_RESULT_OK);
    if (info->matched_comps >= info->interest_comps->n)
        goto Bail;
    if (selfp->intdata != OP_PING &&
        info->pi->prefix_comps != info->matched_comps + morecomps)
        goto Bail;
    if (morecomps == 1) {
        res = ccn_name_comp_get(info->interest_ccnb, info->interest_comps,
                                    info->matched_comps,
                                    &final_comp, &final_size);
        if (res < 0)
            goto Bail;
    }
    
    switch (selfp->intdata & OPER_MASK) {
        case OP_PING:
            reply_body = ccn_charbuf_create();
            freshness = (info->pi->prefix_comps == info->matched_comps) ? 60 : 5;
            break;
        case OP_REG_SELF: 
            reply_body = ccnd_reg_self(ccnd, final_comp, final_size);
            break;
        case OP_NEWFACE:
            reply_body = ccnd_req_newface(ccnd, final_comp, final_size);
            break;
        case OP_PREFIXREG:
            reply_body = ccnd_req_prefixreg(ccnd, final_comp, final_size);
            break;
        default:
            goto Bail;
    }
    if (reply_body == NULL)
        goto Bail;
    
    keystore = ccnd->internal_keys;
    if (keystore == NULL)
        goto Bail;
    msg = ccn_charbuf_create();
    name = ccn_charbuf_create();
    start = info->pi->offset[CCN_PI_B_Name];
    end = info->interest_comps->buf[info->pi->prefix_comps];
    ccn_charbuf_append(name, info->interest_ccnb + start, end - start);
    ccn_charbuf_append_closer(name);
    
    /* Construct a key locator containing the key itself */
    keylocator = ccn_charbuf_create();
    ccnb_element_begin(keylocator, CCN_DTAG_KeyLocator);
    ccnb_element_begin(keylocator, CCN_DTAG_Key);
    res = ccn_append_pubkey_blob(keylocator, ccn_keystore_public_key(keystore));
    ccnb_element_end(keylocator); /* </Key> */
    ccnb_element_end(keylocator); /* </KeyLocator> */
    if (res < 0)
        goto Bail;
    signed_info = ccn_charbuf_create();
    res = ccn_signed_info_create(signed_info,
                                 ccn_keystore_public_key_digest(keystore),
                                 ccn_keystore_public_key_digest_length(keystore),
                                 /*datetime*/NULL,
                                 /*type*/CCN_CONTENT_DATA,
                                 /*freshness*/ freshness,
                                 /*finalblockid*/NULL,
                                 keylocator);
    if (res < 0)
        goto Bail;
    res = ccn_encode_ContentObject(msg, name, signed_info,
                                   reply_body->buf,
                                   reply_body->length, NULL,
                                   ccn_keystore_private_key(keystore));
    if (res < 0)
        goto Bail;
    if ((ccnd->debug & 128) != 0)
        ccnd_debug_ccnb(ccnd, __LINE__, "ccnd_answer_req_response", NULL,
                        msg->buf, msg->length);
    res = ccn_put(info->h, msg->buf, msg->length);
    if (res < 0)
        goto Bail;
    res = CCN_UPCALL_RESULT_INTEREST_CONSUMED;
    goto Finish;
Bail:
    res = CCN_UPCALL_RESULT_ERR;
Finish:
    ccn_charbuf_destroy(&msg);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&keylocator);
    ccn_charbuf_destroy(&reply_body);
    ccn_charbuf_destroy(&signed_info);
    return(res);
}

static int
ccnd_internal_client_refresh(struct ccn_schedule *sched,
               void *clienth,
               struct ccn_scheduled_event *ev,
               int flags)
{
    struct ccnd_handle *ccnd = clienth;
    int microsec;
    if ((flags & CCN_SCHEDULE_CANCEL) != 0)
        return(0);
    if (ccnd->internal_client == NULL)
        return(0);
    microsec = ccn_process_scheduled_operations(ccnd->internal_client);
    if (microsec > ev->evint)
        microsec = ev->evint;
    return(microsec);
}

#define CCND_ID_TEMPL "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

static void
ccnd_uri_listen(struct ccnd_handle *ccnd, const char *uri,
                ccn_handler p, intptr_t intdata)
{
    struct ccn_charbuf *name;
    struct ccn_charbuf *uri_modified = NULL;
    struct ccn_closure *closure;
    struct ccn_keystore *keystore;
    struct ccn_indexbuf *comps;
    const unsigned char *comp;
    size_t comp_size;
    size_t offset;
    
    name = ccn_charbuf_create();
    ccn_name_from_uri(name, uri);
    comps = ccn_indexbuf_create();
    if (ccn_name_split(name, comps) < 0)
        abort();
    if (ccn_name_comp_get(name->buf, comps, 1, &comp, &comp_size) >= 0) {
        if (comp_size == 32 && 0 == memcmp(comp, CCND_ID_TEMPL, 32)) {
            /* Replace placeholder with our ccnd_id*/
            keystore = ccnd->internal_keys;
            offset = comp - name->buf;
            memcpy(name->buf + offset, ccnd->ccnd_id, 32);
            uri_modified = ccn_charbuf_create();
            ccn_uri_append(uri_modified, name->buf, name->length, 1);
            uri = (char *)uri_modified->buf;
        }
    }
    closure = calloc(1, sizeof(*closure));
    closure->p = p;
    closure->data = ccnd;
    closure->intdata = intdata;
    /* To bootstrap, we need to register explicitly */
    ccnd_reg_uri(ccnd, uri,
                 0, /* special faceid for internal client */
                 CCN_FORW_CHILD_INHERIT,
                 0x7FFFFFFF);
    ccn_set_interest_filter(ccnd->internal_client, name, closure);
    ccn_charbuf_destroy(&name);
    ccn_charbuf_destroy(&uri_modified);
    ccn_indexbuf_destroy(&comps);
}

#ifndef CCN_PATH_VAR_TMP
#define CCN_PATH_VAR_TMP "/var/tmp"
#endif

/*
 * This is used to shroud the contents of the keystore, which mainly serves
 * to add integrity checking and defense against accidental misuse.
 * The file permissions serve for restricting access to the private keys.
 */
#ifndef CCND_KEYSTORE_PASS
#define CCND_KEYSTORE_PASS "\010\043\103\375\327\237\152\351\155"
#endif

int
ccnd_init_internal_keystore(struct ccnd_handle *ccnd)
{
    struct ccn_charbuf *temp = NULL;
    struct ccn_charbuf *cmd = NULL;
    struct ccn_keystore *keystore = NULL;
    struct stat statbuf;
    int res = -1;
    size_t save;
    char *keystore_path = NULL;
    FILE *passfile;
    
    if (ccnd->internal_keys != NULL)
        return(0);
    keystore = ccn_keystore_create();
    temp = ccn_charbuf_create();
    cmd = ccn_charbuf_create();
    ccn_charbuf_putf(temp, CCN_PATH_VAR_TMP "/.ccn-user%d/", (int)geteuid());
    res = stat(ccn_charbuf_as_string(temp), &statbuf);
    if (res == -1) {
        if (errno == ENOENT) {
            res = mkdir(ccn_charbuf_as_string(temp), 0700);
            if (res != 0) {
                perror(ccn_charbuf_as_string(temp));
                goto Finish;
            }
        }
        else {
            perror(ccn_charbuf_as_string(temp));
            goto Finish;
        }
    }
    save = temp->length;
    ccn_charbuf_putf(temp, ".ccnd_keystore_%s", ccnd->portstr);
    keystore_path = strdup(ccn_charbuf_as_string(temp));
    res = ccn_keystore_init(keystore, keystore_path, CCND_KEYSTORE_PASS);
    if (res == 0) {
        ccnd->internal_keys = keystore;
        keystore = NULL;
        goto Finish;
    }
    /* No stored keystore that we can access; create one. */
    temp->length = save;
    ccn_charbuf_putf(temp, "p");
    passfile = fopen(ccn_charbuf_as_string(temp), "wb");
    fprintf(passfile, "%s", CCND_KEYSTORE_PASS);
    fclose(passfile);
    ccn_charbuf_putf(cmd, "%s-init-keystore-helper %s",
                     ccnd->progname, keystore_path);
    res = system(ccn_charbuf_as_string(cmd));
    if (res != 0) {
        perror(ccn_charbuf_as_string(cmd));
        goto Finish;
    }
    res = ccn_keystore_init(keystore, keystore_path, CCND_KEYSTORE_PASS);
    if (res == 0) {
        ccnd->internal_keys = keystore;
        keystore = NULL;
    }
Finish:
    if (ccnd->internal_keys != NULL) {
        if (ccn_keystore_public_key_digest_length(ccnd->internal_keys) !=
            sizeof(ccnd->ccnd_id))
            abort();
        memcpy(ccnd->ccnd_id,
               ccn_keystore_public_key_digest(ccnd->internal_keys),
               sizeof(ccnd->ccnd_id));
    }
    ccn_charbuf_destroy(&temp);
    ccn_charbuf_destroy(&cmd);
    if (keystore_path != NULL)
        free(keystore_path);
    if (keystore != NULL)
        ccn_keystore_destroy(&keystore);
    return(res);
}

int
ccnd_internal_client_start(struct ccnd_handle *ccnd)
{
    struct ccn *h;
    if (ccnd->internal_client != NULL)
        return(-1);
    if (ccnd->face0 == NULL)
        abort();
    if (ccnd_init_internal_keystore(ccnd) < 0)
        return(-1);
    ccnd->internal_client = h = ccn_create();
    ccnd_uri_listen(ccnd, "ccn:/ccn/ping",
                    &ccnd_answer_req, OP_PING);
    ccnd_uri_listen(ccnd, "ccn:/ccn/" CCND_ID_TEMPL "/ping",
                    &ccnd_answer_req, OP_PING);
    ccnd_uri_listen(ccnd, "ccn:/ccn/reg/self",
                    &ccnd_answer_req, OP_REG_SELF + 1);
    ccnd_uri_listen(ccnd, "ccn:/ccn/" CCND_ID_TEMPL "/newface",
                    &ccnd_answer_req, OP_NEWFACE + 1);
    ccnd_uri_listen(ccnd, "ccn:/ccn/" CCND_ID_TEMPL "/prefixreg",
                    &ccnd_answer_req, OP_PREFIXREG + 1);
    ccnd->internal_client_refresh =
    ccn_schedule_event(ccnd->sched, 200000,
                       ccnd_internal_client_refresh,
                       NULL, CCN_INTEREST_LIFETIME_MICROSEC);
    return(0);
}

void
ccnd_internal_client_stop(struct ccnd_handle *ccnd)
{
    ccn_destroy(&ccnd->internal_client);
    if (ccnd->internal_client_refresh != NULL) {
        ccnd->internal_client_refresh->evint = 0;
        ccnd->internal_client_refresh = NULL;
    }
}
