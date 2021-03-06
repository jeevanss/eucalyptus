// -*- mode: C; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil -*-
// vim: set softtabstop=4 shiftwidth=4 tabstop=4 expandtab:

/*************************************************************************
 * Copyright 2009-2012 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

//!
//! @file util/euca_auth.c
//! Need to provide description
//!

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                                  INCLUDES                                  |
 |                                                                            |
\*----------------------------------------------------------------------------*/

#define _FILE_OFFSET_BITS                        64 //!< so large-file support works on 32-bit systems

#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <string.h>
#include <openssl/sha.h>
#include <openssl/rsa.h>
#include <openssl/pem.h>
#include <openssl/bio.h>
#include <openssl/evp.h>

#include "eucalyptus.h"
#include "euca_auth.h"
#include "misc.h"                      /* get_string_stats, logprintf */

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                                  DEFINES                                   |
 |                                                                            |
\*----------------------------------------------------------------------------*/

#define FILENAME                                 512    //!< Maximum filename length

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                                  TYPEDEFS                                  |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                                ENUMERATIONS                                |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                                 STRUCTURES                                 |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                             EXTERNAL VARIABLES                             |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/* Should preferably be handled in header file */

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                              GLOBAL VARIABLES                              |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                              STATIC VARIABLES                              |
 |                                                                            |
\*----------------------------------------------------------------------------*/

static boolean initialized = FALSE;    //!< Boolean to make sure we have initialized this module
static char cert_file[FILENAME] = { 0 };    //!< Certificate file name
static char pk_file[FILENAME] = { 0 }; //!< Private key file name

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                             EXPORTED PROTOTYPES                            |
 |                                                                            |
\*----------------------------------------------------------------------------*/

int euca_init_cert(void);
char *euca_get_cert(unsigned char options);

char *base64_enc(unsigned char *in, int size);
char *base64_dec(unsigned char *in, int size);

char *euca_sign_url(const char *verb, const char *date, const char *url);

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                              STATIC PROTOTYPES                             |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                                   MACROS                                   |
 |                                                                            |
\*----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------*\
 |                                                                            |
 |                               IMPLEMENTATION                               |
 |                                                                            |
\*----------------------------------------------------------------------------*/

//!
//! Initialize the certificate authentication module.
//!
int euca_init_cert(void)
{
#define ERR_MSG "Error: required file %s not found by euca_init_cert(). Is $EUCALYPTUS set?\n"
#define OK_MSG  "euca_init_cert(): using file %s\n"
#define CHK_FILE(_n)                           \
{                                              \
	if ((fd = open((_n), O_RDONLY)) < 0) {     \
		LOGERROR(ERR_MSG, (_n));               \
		return (EUCA_ERROR);                   \
	} else {                                   \
		close(fd);                             \
		LOGINFO(OK_MSG, (_n));                 \
	}                                          \
}

    int fd = -1;
    char root[] = "";
    char *euca_home = getenv("EUCALYPTUS");

    if (initialized)
        return (EUCA_OK);

    if (!euca_home) {
        euca_home = root;
    }

    snprintf(cert_file, FILENAME, EUCALYPTUS_KEYS_DIR "/node-cert.pem", euca_home);
    snprintf(pk_file, FILENAME, EUCALYPTUS_KEYS_DIR "/node-pk.pem", euca_home);

    CHK_FILE(cert_file);
    CHK_FILE(pk_file);

    initialized = TRUE;
    return (EUCA_OK);

#undef ERR_MSG
#undef OK_MSG
#undef CHK_FILE
}

//!
//! Retrieves the certificate data from the certificate file
//!
//! @param[in] options bitfield providing certificate manipulation options (CONCATENATE_CERT, INDENT_CERT, TRIM_CERT)
//!
//! @return a new string containing the certificate information or NULL if any error occured.
//!
//! @note caller must free the returned string
//!
char *euca_get_cert(unsigned char options)
{
    int s = 0;
    int fp = -1;
    int got = 0;
    char *cert_str = NULL;
    ssize_t ret = -1;
    struct stat st = { 0 };

    if (!initialized) {
        if (euca_init_cert() != EUCA_OK) {
            return (NULL);
        }
    }

    if (stat(cert_file, &st) != 0) {
        LOGERROR("cannot stat the certificate file %s\n", cert_file);
    } else if ((s = st.st_size * 2) < 1) {  /* *2 because we'll add characters */
        LOGERROR("certificate file %s is too small\n", cert_file);
    } else if ((cert_str = EUCA_ALLOC((s + 1), sizeof(char))) == NULL) {
        LOGERROR("out of memory\n");
    } else if ((fp = open(cert_file, O_RDONLY)) < 0) {
        LOGERROR("failed to open certificate file %s\n", cert_file);
        EUCA_FREE(cert_str);
        cert_str = NULL;
    } else {
        while ((got < s) && ((ret = read(fp, cert_str + got, 1)) == 1)) {
            if (options & CONCATENATE_CERT) {   /* omit all newlines */
                if (cert_str[got] == '\n')
                    continue;
            } else {
                if (options & INDENT_CERT)  /* indent lines 2 through N with TABs */
                    if (cert_str[got] == '\n')
                        cert_str[++got] = '\t';
            }
            got++;
        }

        if (ret != 0) {
            LOGERROR("failed to read whole certificate file %s\n", cert_file);
            EUCA_FREE(cert_str);
            cert_str = NULL;
        } else {
            if (options & TRIM_CERT) {
                if (cert_str[got - 1] == '\t' || cert_str[got - 1] == '\n')
                    got--;
                if (cert_str[got - 1] == '\n')
                    got--;             /* because of indenting */
            }
            cert_str[got] = '\0';
        }
        close(fp);
    }
    return (cert_str);
}

//!
//! Encode a given buffer
//!
//! @param[in] in a pointer to the string buffer to encode
//! @param[in] size the length of the string buffer
//!
//! @return a pointer to the encoded string buffer.
//!
//! @note caller must free the returned string
//!
char *base64_enc(unsigned char *in, int size)
{
    BIO *bio64 = NULL;
    BIO *biomem = NULL;
    char *out_str = NULL;
    BUF_MEM *buf = NULL;

    if ((bio64 = BIO_new(BIO_f_base64())) == NULL) {
        LOGERROR("BIO_new(BIO_f_base64()) failed\n");
    } else {
        BIO_set_flags(bio64, BIO_FLAGS_BASE64_NO_NL);   /* no long-line wrapping */
        if ((biomem = BIO_new(BIO_s_mem())) == NULL) {
            LOGERROR("BIO_new(BIO_s_mem()) failed\n");
        } else {
            bio64 = BIO_push(bio64, biomem);
            if (BIO_write(bio64, in, size) != size) {
                LOGERROR("BIO_write() failed\n");
            } else {
                (void)BIO_flush(bio64);
                BIO_get_mem_ptr(bio64, &buf);
                if ((out_str = EUCA_ALLOC((buf->length + 1), sizeof(char))) == NULL) {
                    LOGERROR("out of memory for Base64 buf\n");
                } else {
                    memcpy(out_str, buf->data, buf->length);
                    out_str[buf->length] = '\0';
                }
            }
        }
        BIO_free_all(bio64);           /* frees both bio64 and biomem */
    }
    return (out_str);
}

//!
//! Decode a given buffer
//!
//! @param[in] in a pointer to the string buffer to decode
//! @param[in] size the length of the string buffer
//!
//! @return a pointer to the decoded string buffer.
//!
//! @note caller must free the returned string
//!
char *base64_dec(unsigned char *in, int size)
{
    BIO *bio64 = NULL;
    BIO *biomem = NULL;
    char *buf = NULL;

    if ((in != NULL) && (size > 0)) {
        if ((bio64 = BIO_new(BIO_f_base64())) == NULL) {
            LOGERROR("BIO_new(BIO_f_base64()) failed\n");
        } else {
            BIO_set_flags(bio64, BIO_FLAGS_BASE64_NO_NL);   /* no long-line wrapping */

            if ((biomem = BIO_new_mem_buf(in, size)) == NULL) {
                LOGERROR("BIO_new_mem_buf() failed\n");
            } else if ((buf = EUCA_ZALLOC(size, sizeof(char))) == NULL) {
                LOGERROR("Memory allocation failure.\n");
            } else {
                biomem = BIO_push(bio64, biomem);

                if ((BIO_read(biomem, buf, size)) <= 0) {
                    LOGERROR("BIO_read() read failed\n");
                    EUCA_FREE(buf);
                }
            }

            BIO_free_all(bio64);
        }
    }

    return (buf);
}

//!
//! Signs an URL address
//!
//! @param[in] verb the verb for the signing algorithm
//! @param[in] date the date
//! @param[in] url a pointer to the URL to sign
//!
//! @return a pointer to the signed URL string buffer
//!
//! @note caller must free the returned string
//!
char *euca_sign_url(const char *verb, const char *date, const char *url)
{
#define BUFSIZE                        2024

    if (!initialized) {
        if (euca_init_cert() != EUCA_OK) {
            return (NULL);
        }
    }

    int ret;

    char input[BUFSIZE] = { 0 };
    char *sig_str = NULL;
    RSA *rsa = NULL;
    FILE *fp = NULL;
    unsigned int siglen = 0;
    unsigned char *sig = NULL;
    unsigned char sha1[SHA_DIGEST_LENGTH] = { 0 };

    if (verb == NULL || date == NULL || url == NULL)
        return (NULL);

    if ((rsa = RSA_new()) == NULL) {
        LOGERROR("RSA_new() failed\n");
    } else if ((fp = fopen(pk_file, "r")) == NULL) {
        LOGERROR("failed to open private key file %s\n", pk_file);
        RSA_free(rsa);
    } else {
        LOGTRACE("reading private key file %s\n", pk_file);
        PEM_read_RSAPrivateKey(fp, &rsa, NULL, NULL);   /* read the PEM-encoded file into rsa struct */
        if (rsa == NULL) {
            LOGERROR("failed to read private key file %s\n", pk_file);
        } else {
            // RSA_print_fp (stdout, rsa, 0); /* (for debugging) */
            if ((sig = EUCA_ALLOC(RSA_size(rsa), sizeof(unsigned char))) == NULL) {
                LOGERROR("out of memory (for RSA key)\n");
            } else {
                /* finally, SHA1 and sign with PK */
                assert((strlen(verb) + strlen(date) + strlen(url) + 4) <= BUFSIZE);
                snprintf(input, BUFSIZE, "%s\n%s\n%s\n", verb, date, url);
                LOGEXTREME("signing input %s\n", get_string_stats(input));
                SHA1((unsigned char *)input, strlen(input), sha1);
                if ((ret = RSA_sign(NID_sha1, sha1, SHA_DIGEST_LENGTH, sig, &siglen, rsa)) != 1) {
                    LOGERROR("RSA_sign() failed\n");
                } else {
                    LOGEXTREME("signing output %d\n", sig[siglen - 1]);
                    sig_str = base64_enc(sig, siglen);
                    LOGEXTREME("base64 signature %s\n", get_string_stats((char *)sig_str));
                }
                EUCA_FREE(sig);
            }
            RSA_free(rsa);
        }
        fclose(fp);
    }

    return (sig_str);

#undef BUFSIZE
}
