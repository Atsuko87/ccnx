/**
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.test;

import java.util.Random;

import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.Signature;
import org.ccnx.ccn.protocol.SignedInfo;
import org.junit.BeforeClass;

/**
 * A place to put generic things needed by different tests
 */
public class CCNTestBase {
	// A signature just used to allow tests to pass validation in which there are no keys
	// corresponding to the publisherID's used.
	static public Signature fakeSignature = null;
	static public SignedInfo fakeSignedInfo = null;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Random rnd = new Random();
		byte [] fakeSigBytes = new byte[128];
		byte [] publisher = new byte[32];
		rnd.nextBytes(fakeSigBytes);
		rnd.nextBytes(publisher);
		PublisherPublicKeyDigest pub = new PublisherPublicKeyDigest(publisher);
		fakeSignature = new Signature(fakeSigBytes);
		CCNTime now = CCNTime.now();
		KeyLocator locator = new KeyLocator(ContentName.fromNative("/key/" + pub.digest().toString()));
		fakeSignedInfo = new SignedInfo(pub, now, SignedInfo.ContentType.DATA, locator);
	}
}
