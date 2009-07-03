
/*****************************************************************************
 * ccn.c: CCN input module
 *****************************************************************************
 * Copyright (C) 2009, Palo Alto Research Center
 * $Id:$
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston3 MA 02110-1301, USA.
 *****************************************************************************/

/*****************************************************************************
 * Preamble
 *****************************************************************************/

#ifdef HAVE_CONFIG_H
# include "config.h"
#endif

#include <vlc_common.h>
#include <vlc_plugin.h>
#include <vlc_access.h>
#include <vlc_url.h>

#include <ccn/ccn.h>
#include <ccn/charbuf.h>
#include <ccn/uri.h>

/*****************************************************************************
 * Disable internationalization
 *****************************************************************************/
#define _(str) (str)
#define N_(str) (str)
/*****************************************************************************
 * Module descriptor
 *****************************************************************************/
#define CACHING_TEXT N_("Caching value in ms")
#define CACHING_LONGTEXT N_( \
    "Caching value for CCN streams. This " \
    "value should be set in milliseconds.")

static int  CCNOpen(vlc_object_t *);
static void CCNClose(vlc_object_t *);
static block_t *CCNBlock(access_t *);
static int CCNSeek(access_t *, int64_t);
static int CCNControl(access_t *, int, va_list);

static void *ccn_event_thread(vlc_object_t *p_this);

vlc_module_begin();
    set_shortname(N_("CCN"));
    set_description(N_("CCN input"));
    set_category(CAT_INPUT);
    set_subcategory(SUBCAT_INPUT_ACCESS);
    add_integer("ccn-caching", DEFAULT_PTS_DELAY / 1000, NULL,
                CACHING_TEXT, CACHING_LONGTEXT, true);
    change_safe();
    set_capability("access", 0);
    add_shortcut("ccn");
    set_callbacks(CCNOpen, CCNClose);
vlc_module_end();

/*****************************************************************************
 * Local prototypes
 *****************************************************************************/
#define CCN_FIFO_MAX (2 * 1024 * 1024)
struct access_sys_t
{
    vlc_url_t  url;
    block_fifo_t *p_fifo;
    struct ccn *ccn;
    struct ccn_closure *incoming;
};

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info);
/*****************************************************************************
 * Open: 
 *****************************************************************************/
static int CCNOpen(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = NULL;
    struct ccn_charbuf *p_name = NULL;
    int i_ret = 0;
    int i_err = VLC_EGENERIC;

    /* Init p_access */
    access_InitFields(p_access);
    ACCESS_SET_CALLBACKS(NULL, CCNBlock, CCNControl, CCNSeek);
    p_access->p_sys = calloc(1, sizeof(access_sys_t));
    p_sys = p_access->p_sys;
    if (p_sys == NULL)
        return VLC_ENOMEM;
    p_access->info.b_prebuffered = true;
    p_access->info.i_size = -1;
    /* Update default_pts */
    var_Create(p_access, "ccn-caching", VLC_VAR_INTEGER | VLC_VAR_DOINHERIT);
    p_sys->incoming = calloc(1, sizeof(struct ccn_closure));
    if (p_sys->incoming == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
    msg_Dbg(p_access, "CCN.Open %s, closure 0x%08x",
            p_access->psz_path, (int)p_sys->incoming);    /*XXX: fix format */
    p_sys->incoming->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->incoming->p = &incoming_content; /* the CCN callback */
    p_sys->ccn = ccn_create();
    if (p_sys->ccn == NULL || ccn_connect(p_sys->ccn, NULL) == -1) {
        goto exit_error;
    }

    p_name = ccn_charbuf_create();
    if (p_name == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
    i_ret = ccn_name_from_uri(p_name, p_access->psz_path);
    if (i_ret < 0) {
        goto exit_error;
    }
    i_ret = ccn_name_append_numeric(p_name, CCN_MARKER_SEQNUM, 0);
    if (i_ret < 0) {
        goto exit_error;
    }
    i_ret = ccn_express_interest(p_sys->ccn, p_name, -1, p_sys->incoming, NULL);
    if (i_ret < 0) {
        goto exit_error;
    }
    ccn_charbuf_destroy(&p_name);

    p_sys->p_fifo = block_FifoNew();
    if (p_sys->p_fifo == NULL) {
        i_err = VLC_ENOMEM;
        goto exit_error;
    }
    i_ret = vlc_thread_create(p_access, "CCN run thread", ccn_event_thread,
                      VLC_THREAD_PRIORITY_INPUT, false);
    if (i_ret == 0)
        return VLC_SUCCESS;

 exit_error:
    msg_Err(p_access, "CCN.Open failed");
    if (p_sys->p_fifo) {
        block_FifoRelease(p_sys->p_fifo);
        p_sys->p_fifo = NULL;
    }
    ccn_charbuf_destroy(&p_name);
    if (p_sys->incoming) {
        free(p_sys->incoming);
        p_sys->incoming = NULL;
    }
    ccn_destroy(&(p_sys->ccn));
    free(p_sys);
    return (i_err);
}

/*****************************************************************************
 * CCNClose: free unused data structures
 *****************************************************************************/
static void CCNClose(vlc_object_t *p_this)
{
    access_t     *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;

    msg_Dbg(p_access, "CCN.Close called");
    vlc_object_kill(p_access); 
    if (p_sys->p_fifo)
        block_FifoWake(p_sys->p_fifo);
    vlc_thread_join(p_access);
    if (p_sys->p_fifo) {
        block_FifoRelease(p_sys->p_fifo);
        p_sys->p_fifo = NULL;
    }
    ccn_destroy(&(p_sys->ccn));
    free(p_sys);
}

/*****************************************************************************
 * CCNBlock:
 *****************************************************************************/
static block_t *CCNBlock(access_t *p_access)
{
    access_sys_t *p_sys = p_access->p_sys;
    block_t *p_block = NULL;

    if( p_access->info.b_eof ) {
        msg_Dbg(p_access, "CCN.Block eof");
        return NULL;
    }
    p_block = block_FifoGet(p_sys->p_fifo);
    if (p_block == NULL)
        return NULL;

    p_access->info.i_pos += p_block->i_buffer;
    if (p_block->i_buffer == 0)
        p_access->info.b_eof = true;

    return (p_block);
}
#if 0
/* this is not needed, blocks work better... but just in case... */

/*****************************************************************************
 * CCNRead:
 *****************************************************************************/
static ssize_t CCNRead(access_t *p_access, uint8_t *buf, size_t size)
{
    access_sys_t *p_sys = p_access->p_sys;
    block_t *p_block;
    size_t block_size = 0;
    size_t result_size = 0;



    msg_Dbg(p_access, "CCN Read size %d @ %lld", size, p_access->info.i_pos);
    while(result_size < size) {
        p_block = block_FifoShow(p_sys->p_fifo);
        if (p_block == NULL) 
            return (result_size);
        block_size = p_block->i_buffer;
        if (block_size == 0) {
            p_access->info.b_eof = true;
            return (result_size);
        }
        if (block_size <= (size - result_size)) {
            p_block = block_FifoGet(p_sys->p_fifo);
            memcpy(buf + result_size, p_block->p_buffer, block_size);
            block_Release(p_block);
            p_access->info.i_pos += block_size;
            result_size += block_size;
            msg_Dbg(p_access, "CCN Read: used all of block of %d bytes", block_size);
        } else {
            int used = size - result_size;
            memcpy(buf + result_size, p_block->p_buffer, used);
            p_block->p_buffer += used;
            p_block->i_buffer -= used;
            p_access->info.i_pos += used;
            result_size += used;
            msg_Dbg(p_access, "CCN Read: used %d of block of %d bytes", used, block_size);
        }
    }
    return (result_size);
}
#endif
/*****************************************************************************
 * CCNSeek:
 *****************************************************************************/
#define CCN_CHUNK_SIZE 4096

static int CCNSeek(access_t *p_access, int64_t i_pos)
{
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn_charbuf *p_name;
    int i_ret;

    /* flush the FIFO, restart from the specified point */
    block_FifoEmpty(p_sys->p_fifo);
    p_sys->incoming = calloc(1, sizeof(struct ccn_closure));
    if (p_sys->incoming == NULL) {
        return (VLC_EGENERIC);
    }
    msg_Dbg(p_access, "CCN.Seek to %"PRId64", closure 0x%08x", i_pos, (int) p_sys->incoming);
    p_sys->incoming->data = p_access; /* so CCN callbacks can find p_sys */
    p_sys->incoming->p = &incoming_content; /* the CCN callback */
    p_sys->incoming->intdata = i_pos;

    p_name = ccn_charbuf_create();
    i_ret = ccn_name_from_uri(p_name, p_access->psz_path);
    if (i_ret < 0) {
        ccn_charbuf_destroy(&p_name);
        return (VLC_EGENERIC);
    }
    ccn_name_append_numeric(p_name, CCN_MARKER_SEQNUM, p_sys->incoming->intdata / CCN_CHUNK_SIZE);
    ccn_express_interest(p_sys->ccn, p_name, -1, p_sys->incoming, NULL);
    ccn_charbuf_destroy(&p_name);    
    p_access->info.i_pos = i_pos;
    p_access->info.b_eof = false;
    return (VLC_SUCCESS);
}
/*****************************************************************************
 * Control:
 *****************************************************************************/
static int CCNControl(access_t *p_access, int i_query, va_list args)
{
    access_sys_t *p_sys = p_access->p_sys;
    bool   *pb_bool;
    int          *pi_int;
    int64_t      *pi_64;

    switch(i_query)
    {
        case ACCESS_CAN_SEEK:
        case ACCESS_CAN_FASTSEEK:
        case ACCESS_CAN_CONTROL_PACE:
        case ACCESS_CAN_PAUSE:
            pb_bool = (bool*)va_arg(args, bool *);
            *pb_bool = true;
            break;

        case ACCESS_GET_MTU:
            pi_int = (int*)va_arg(args, int *);
            *pi_int = 0;
            break;

        case ACCESS_GET_PTS_DELAY:
            pi_64 = (int64_t*)va_arg(args, int64_t *);
            *pi_64 = (int64_t)var_GetInteger(p_access, "ccn-caching") * INT64_C(1000);
            break;

        case ACCESS_SET_PAUSE_STATE:
            pb_bool = (bool*)va_arg(args, bool *);
            break;

        case ACCESS_GET_TITLE_INFO:
	case ACCESS_GET_META:
        case ACCESS_SET_TITLE:
        case ACCESS_SET_SEEKPOINT:
        case ACCESS_SET_PRIVATE_ID_STATE:
    	case ACCESS_SET_PRIVATE_ID_CA:
        case ACCESS_GET_PRIVATE_ID_STATE:
        case ACCESS_GET_CONTENT_TYPE:
            return VLC_EGENERIC;

        default:
            msg_Warn(p_access, "CCN unimplemented query in control - %d", i_query);
            return VLC_EGENERIC;

    }
    return VLC_SUCCESS;
}

static void *ccn_event_thread(vlc_object_t *p_this)
{
    access_t *p_access = (access_t *)p_this;
    access_sys_t *p_sys = p_access->p_sys;
    struct ccn *ccn = p_sys->ccn;
    int res = 0;

    while (res >= 0 && vlc_object_alive(p_access)) {
        res = ccn_run(ccn, 500);
    }
}

enum ccn_upcall_res
incoming_content(struct ccn_closure *selfp,
                 enum ccn_upcall_kind kind,
                 struct ccn_upcall_info *info)
{
    access_t *p_access = (access_t *)(selfp->data);
    access_sys_t *p_sys = p_access->p_sys;
    int start_offset = 0;
    block_t *p_block = NULL;
    bool b_last = false;
    struct ccn_charbuf *name = NULL;
    struct ccn_charbuf *templ = NULL;
    const unsigned char *ccnb = NULL;
    size_t ccnb_size = 0;
    const unsigned char *data = NULL;
    size_t data_size = 0;
    size_t written;
    const unsigned char *ib = NULL; /* info->interest_ccnb */
    struct ccn_indexbuf *ic = NULL;
    int res;

    switch (kind) {
    case CCN_UPCALL_FINAL:
        msg_Dbg(p_access, "CCN upcall final 0x%08x", (int) selfp);
        if (selfp == p_sys->incoming)
            p_sys->incoming = NULL;
        free(selfp);
        return(CCN_UPCALL_RESULT_OK);
    case CCN_UPCALL_INTEREST_TIMED_OUT:
        if (selfp != p_sys->incoming) {
            msg_Dbg(p_access, "CCN Interest timed out on dead closure 0x%08x", (int)selfp);
            return(CCN_UPCALL_RESULT_OK);
        }
        msg_Dbg(p_access, "CCN upcall reexpress -- timed out");
        return(CCN_UPCALL_RESULT_REEXPRESS); // XXX - may need to reseed bloom filter
    case CCN_UPCALL_CONTENT_UNVERIFIED:
    case CCN_UPCALL_CONTENT:
        if (selfp != p_sys->incoming) {
            msg_Dbg(p_access, "CCN content on dead closure 0x%08x", (int)selfp);
            return(CCN_UPCALL_RESULT_OK);
        }
        break;
    default:
        msg_Warn(p_access, "CCN upcall result error");
        return(CCN_UPCALL_RESULT_ERR);
    }

    ccnb = info->content_ccnb;
    ccnb_size = info->pco->offset[CCN_PCO_E];
    ib = info->interest_ccnb;
    ic = info->interest_comps;
    res = ccn_content_get_value(ccnb, ccnb_size, info->pco, &data, &data_size);
    if (res < 0) abort();

#if 0
    if (info->pco->type != CCN_CONTENT_DATA) {
        /* This is spam, so need to try again excluding this one. */
        name = ccn_charbuf_create();
        ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 1]);
        note_new_exclusion(p_sys, ccnb,
                           info->pco->offset[CCN_PCO_B_Signature],
                           info->pco->offset[CCN_PCO_E_Signature]);
        templ = make_template(p_sys, info);
        res = ccn_express_interest(info->h, name, -1, selfp, templ);
        /* TODO: must not abort... */
        if (res < 0)
            abort();
        ccn_charbuf_destroy(&templ);
        ccn_charbuf_destroy(&name);
        return(CCN_UPCALL_RESULT_OK);
    }
#endif

    /* was this the last block? */
    /* TODO:  the test below should get refactored into the library */
    if (info->pco->offset[CCN_PCO_B_FinalBlockID] !=
        info->pco->offset[CCN_PCO_E_FinalBlockID]) {
        const unsigned char *finalid = NULL;
        size_t finalid_size = 0;
        const unsigned char *nameid = NULL;
        size_t nameid_size = 0;
        struct ccn_indexbuf *cc = info->content_comps;
        ccn_ref_tagged_BLOB(CCN_DTAG_FinalBlockID, ccnb,
                            info->pco->offset[CCN_PCO_B_FinalBlockID],
                            info->pco->offset[CCN_PCO_E_FinalBlockID],
                            &finalid,
                            &finalid_size);
        if (cc->n < 2) abort();
        ccn_ref_tagged_BLOB(CCN_DTAG_Component, ccnb,
                            cc->buf[cc->n - 2],
                            cc->buf[cc->n - 1],
                            &nameid,
                            &nameid_size);
        if (finalid_size == nameid_size && 0 == memcmp(finalid, nameid, nameid_size)) {
            b_last = true;
        }
    }
    
    /* a short block can also indicate the end, if the client isn't using FinalBlockID */
    if (data_size < CCN_CHUNK_SIZE)
        b_last = true;
    /* something to process */
    if (data_size > 0) {
        start_offset = selfp->intdata % CCN_CHUNK_SIZE;
        if (start_offset > data_size) {
            msg_Err(p_access, "start_offset %d > data_size %d", start_offset, data_size);
        } else {
            p_block = block_New(p_access, data_size - start_offset);
            memcpy(p_block->p_buffer, data + start_offset, data_size - start_offset);
            block_FifoPut(p_sys->p_fifo, p_block);
        }
    }

    /* if we're done, indicate so with a 0-byte block, and don't express an interest */
    if (b_last) {
        block_FifoPut(p_sys->p_fifo, block_New(p_access, 0));
        return (CCN_UPCALL_RESULT_OK);
    }

    /* need to do this with a condition variable, since we don't want to sleep the thread */
    while (block_FifoSize(p_sys->p_fifo) > CCN_FIFO_MAX) {
        msleep(1000);
        if (!vlc_object_alive(p_access)) return(CCN_UPCALL_RESULT_OK);
    }
    /* Ask for the next fragment */
    name = ccn_charbuf_create();
    ccn_name_init(name);
    if (ic->n < 2) abort();
    res = ccn_name_append_components(name, ib, ic->buf[0], ic->buf[ic->n - 2]);
    if (res < 0) abort();
    selfp->intdata = CCN_CHUNK_SIZE * (1 + selfp->intdata / CCN_CHUNK_SIZE);
    ccn_name_append_numeric(name, CCN_MARKER_SEQNUM, selfp->intdata / CCN_CHUNK_SIZE);
#if 0
    clear_excludes(p_sys);
    templ = make_template(p_sys, info);

    res = ccn_express_interest(info->h, name, -1, selfp, templ);
#else
    res = ccn_express_interest(info->h, name, -1, selfp, NULL);
#endif
    if (res < 0) abort();
#if 0
    ccn_charbuf_destroy(&templ);
#endif
    ccn_charbuf_destroy(&name);
    
    return(CCN_UPCALL_RESULT_OK);
}
