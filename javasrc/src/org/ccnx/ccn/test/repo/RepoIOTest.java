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

package org.ccnx.ccn.test.repo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.repo.BasicPolicy;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.RepositoryOutputStream;
import org.ccnx.ccn.io.RepositoryVersionedOutputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;



/**
 * Part of repository test infrastructure. Requires at least repository to be running,
 * and RFSTest to have been run.
 */
public class RepoIOTest extends RepoTestBase {
	
	protected static String _repoTestDir = "repotest";
	protected static byte [] data = new byte[4000];
	protected static String _testPrefix = "/testNameSpace/stream";
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		//Library.setLevel(Level.FINEST);
		_testPrefix += "-" + rand.nextInt(10000);
		RepoTestBase.setUpBeforeClass();
		byte value = 1;
		for (int i = 0; i < data.length; i++)
			data[i] = value++;
		RepositoryOutputStream ros = new RepositoryOutputStream(ContentName.fromNative(_testPrefix), putLibrary); 
		ros.setBlockSize(100);
		ros.setTimeout(4000);
		ros.write(data, 0, data.length);
		ros.close();
	}
	
	@AfterClass
	public static void cleanup() throws Exception {
	}
	
	@Before
	public void setUp() throws Exception {
	}
	
	@Test
	public void testPolicyViaCCN() throws Exception {
		System.out.println("Testing namespace policy setting");
		checkNameSpace("/repoTest/data2", true);
		changePolicy("/org/ccnx/ccn/test/repo/policyTest.xml");
		checkNameSpace("/repoTest/data3", false);
		checkNameSpace("/testNameSpace/data1", true);
		changePolicy("/org/ccnx/ccn/test/repo/origPolicy.xml");
		checkNameSpace("/repoTest/data4", true);
	}
	
	@Test
	public void testReadFromRepo() throws Exception {
		System.out.println("Testing reading a stream from the repo");
		Thread.sleep(5000);
		CCNInputStream input = new CCNInputStream(ContentName.fromNative(_testPrefix), getLibrary);
		byte[] testBytes = new byte[data.length];
		input.read(testBytes);
		Assert.assertArrayEquals(data, testBytes);
	}
	
	@Test
	// The purpose of this test is to do versioned reads from repo
	// of data not already in the ccnd cache, thus testing 
	// what happens if we pull latest version and try to read
	// content in order
	public void testVersionedRead() throws InterruptedException, MalformedContentNameStringException, XMLStreamException, IOException {
		System.out.println("Testing reading a versioned stream");
		Thread.sleep(5000);
		ContentName versionedNameNormal = ContentName.fromNative("/testNameSpace/testVersionNormal");
		CCNVersionedInputStream vstream = new CCNVersionedInputStream(versionedNameNormal);
		InputStreamReader reader = new InputStreamReader(vstream);
		for (long i=SegmentationProfile.baseSegment(); i<5; i++) {
			String segmentContent = "segment"+ new Long(i).toString();
			char[] cbuf = new char[8];
			int count = reader.read(cbuf, 0, 8);
			System.out.println("for " + i + " got " + count + " (eof " + vstream.eof() + "): " + new String(cbuf));
			Assert.assertEquals(segmentContent, new String(cbuf));
		}
		Assert.assertEquals(-1, reader.read());
	}
	
	private void changePolicy(String policyFile) throws Exception {
		FileInputStream fis = new FileInputStream(_topdir + policyFile);
		byte [] content = new byte[fis.available()];
		fis.read(content);
		fis.close();
		ContentName basePolicy = BasicPolicy.getPolicyName(ContentName.fromNative(_globalPrefix), _repoName);
		ContentName policyName = new ContentName(basePolicy, Interest.generateNonce());
		RepositoryVersionedOutputStream rfos = new RepositoryVersionedOutputStream(policyName, putLibrary);
		rfos.write(content, 0, content.length);
		rfos.close();
		Thread.sleep(4000);
	}
}
