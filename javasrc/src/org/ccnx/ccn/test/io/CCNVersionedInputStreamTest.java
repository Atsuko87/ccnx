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

package org.ccnx.ccn.test.io;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.io.CCNReader;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


public class CCNVersionedInputStreamTest {
	
	static ContentName defaultStreamName;
	static ContentName firstVersionName;
	static int firstVersionLength;
	static int firstVersionMaxSegment;
	static byte [] firstVersionDigest;
	static ContentName middleVersionName;
	static int middleVersionLength;
	static int middleVersionMaxSegment;
	static byte [] middleVersionDigest;
	static ContentName latestVersionName;
	static int latestVersionLength;
	static int latestVersionMaxSegment;
	static byte [] latestVersionDigest;
	static CCNHandle outputHandle;
	static CCNHandle inputHandle;
	static CCNReader reader;
	static final int MAX_FILE_SIZE = 1024*1024; // 1 MB
	static final int BUF_SIZE = 4096;
	

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Random randBytes = new Random(); // doesn't need to be secure
		outputHandle = CCNHandle.open();
		inputHandle = CCNHandle.open();
		reader = new CCNReader(inputHandle);
		
		// Write a set of output
		defaultStreamName = ContentName.fromNative("/test/stream/versioning/LongOutput.bin");
		
		firstVersionName = VersioningProfile.addVersion(defaultStreamName);
		firstVersionLength = randBytes.nextInt(MAX_FILE_SIZE);
		firstVersionMaxSegment = (int)Math.ceil(firstVersionLength/SegmentationProfile.DEFAULT_BLOCKSIZE);
		firstVersionDigest = writeFileFloss(firstVersionName, firstVersionLength, randBytes);
		
		middleVersionName = VersioningProfile.addVersion(defaultStreamName);
		middleVersionLength = randBytes.nextInt(MAX_FILE_SIZE);
		middleVersionMaxSegment = (int)Math.ceil(middleVersionLength/SegmentationProfile.DEFAULT_BLOCKSIZE);
		middleVersionDigest = writeFileFloss(middleVersionName, middleVersionLength, randBytes);

		latestVersionName = VersioningProfile.addVersion(defaultStreamName);
		latestVersionLength = randBytes.nextInt(MAX_FILE_SIZE);
		latestVersionMaxSegment = (int)Math.ceil(latestVersionLength/SegmentationProfile.DEFAULT_BLOCKSIZE);
		latestVersionDigest = writeFileFloss(latestVersionName, latestVersionLength, randBytes);
		
	}
	
	/**
	 * Trick to get around lack of repo. We want the test below to read data out of
	 * ccnd. Problem is to do that, we have to get it into ccnd. This pre-loads
	 * ccnd with data by "flossing" it -- starting up a reader thread that will
	 * pull our generated data into ccnd for us, where it will wait till we read
	 * it back out.
	 * @param completeName
	 * @param fileLength
	 * @param randBytes
	 * @return
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	public static byte [] writeFileFloss(ContentName completeName, int fileLength, Random randBytes) throws XMLStreamException, IOException, NoSuchAlgorithmException {
		CCNOutputStream stockOutputStream = new CCNOutputStream(completeName, outputHandle);
		
		DigestOutputStream digestStreamWrapper = new DigestOutputStream(stockOutputStream, MessageDigest.getInstance("SHA1"));
		byte [] bytes = new byte[BUF_SIZE];
		int elapsed = 0;
		int nextBufSize = 0;
		boolean firstBuf = true;
		System.out.println("Writing file: " + completeName + " bytes: " + fileLength);
		final double probFlush = .3;
		
		while (elapsed < fileLength) {
			nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
			randBytes.nextBytes(bytes);
			digestStreamWrapper.write(bytes, 0, nextBufSize);
			elapsed += nextBufSize;
			if (firstBuf) {
				startReader(completeName, fileLength);
				firstBuf = false;
			}
			System.out.println(completeName + " wrote " + elapsed + " out of " + fileLength + " bytes.");
			if (randBytes.nextDouble() < probFlush) {
				System.out.println("Flushing buffers.");
				digestStreamWrapper.flush();
			}
		}
		digestStreamWrapper.close();
		System.out.println("Finished writing file " + completeName);
		return digestStreamWrapper.getMessageDigest().digest();
	}
	
	public static void startReader(final ContentName completeName, final int fileLength) {
		new Thread(){
	        public void run() {
	           try {
				readFile(completeName, fileLength);
			} catch (Exception e) {
				e.printStackTrace();
				Assert.fail("Class setup failed! " + e.getClass().getName() + ": " + e.getMessage());
			} 
	        }
	    }.start();
	}
	
	public static byte [] readFile(ContentName completeName, int fileLength) throws XMLStreamException, IOException {
		CCNInputStream inputStream = new CCNInputStream(completeName);
		System.out.println("Reading file : " + completeName);
		return readFile(inputStream, fileLength);
	}
	
	public static byte [] readFile(InputStream inputStream, int fileLength) throws IOException, XMLStreamException {
		
		DigestInputStream dis = null;
		try {
			dis = new DigestInputStream(inputStream, MessageDigest.getInstance("SHA1"));
		} catch (NoSuchAlgorithmException e) {
			Log.severe("No SHA1 available!");
			Assert.fail("No SHA1 available!");
		}
		int elapsed = 0;
		int read = 0;
		byte [] bytes = new byte[BUF_SIZE];
		while (elapsed < fileLength) {
			read = dis.read(bytes);
			if (read < 0) {
				System.out.println("EOF read at " + elapsed + " bytes out of " + fileLength);
				break;
			} else if (read == 0) {
				System.out.println("0 bytes read at " + elapsed + " bytes out of " + fileLength);
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					
				}
			}
			elapsed += read;
			System.out.println(" read " + elapsed + " bytes out of " + fileLength);
		}
		return dis.getMessageDigest().digest();
	}
	
	@Test
	public void testCCNVersionedInputStreamContentNameLongPublisherKeyIDCCNLibrary() throws Exception {
		// we can make a new handle; as long as we don't use the outputHandle it should work
		CCNVersionedInputStream vfirst = 
			new CCNVersionedInputStream(firstVersionName, 
					((3 > firstVersionMaxSegment) ? firstVersionMaxSegment : 3L), outputHandle.getDefaultPublisher(), inputHandle);
		CCNVersionedInputStream vlatest = new CCNVersionedInputStream(defaultStreamName, 
				((3 > latestVersionMaxSegment) ? latestVersionMaxSegment : 3L), outputHandle.getDefaultPublisher(), inputHandle);
		testArgumentRunner(vfirst, vlatest);
	}

	@Test
	public void testCCNVersionedInputStreamContentNamePublisherKeyIDCCNLibrary() throws Exception {
		// we can make a new handle; as long as we don't use the outputHandle it should work
		CCNVersionedInputStream vfirst = new CCNVersionedInputStream(firstVersionName, outputHandle.getDefaultPublisher(), inputHandle);
		CCNVersionedInputStream vlatest = new CCNVersionedInputStream(defaultStreamName, outputHandle.getDefaultPublisher(), inputHandle);
		testArgumentRunner(vfirst, vlatest);
	}

	@Test
	public void testCCNVersionedInputStreamContentName() throws Exception {
		// we can make a new handle; as long as we don't use the outputHandle it should work
		CCNVersionedInputStream vfirst = new CCNVersionedInputStream(firstVersionName);
		CCNVersionedInputStream vlatest = new CCNVersionedInputStream(defaultStreamName);
		testArgumentRunner(vfirst, vlatest);
	}

	@Test
	public void testCCNVersionedInputStreamContentNameCCNLibrary() throws Exception {
		
		CCNVersionedInputStream vfirst = new CCNVersionedInputStream(firstVersionName, inputHandle);
		CCNVersionedInputStream vlatest = new CCNVersionedInputStream(defaultStreamName, inputHandle);
		testArgumentRunner(vfirst, vlatest);
	}
	
	protected void testArgumentRunner(CCNVersionedInputStream vfirst,
									  CCNVersionedInputStream vlatest) throws Exception {
		Assert.assertEquals(vfirst.getBaseName(), firstVersionName);
		Assert.assertEquals(VersioningProfile.cutTerminalVersion(vfirst.getBaseName()).first(), defaultStreamName);
		byte b = (byte)vfirst.read();
		if (b != 0x00) {
		}
		Assert.assertEquals(VersioningProfile.getLastVersionAsTimestamp(firstVersionName), 
				VersioningProfile.getLastVersionAsTimestamp(vfirst.getBaseName()));
		Assert.assertEquals(VersioningProfile.getLastVersionAsTimestamp(firstVersionName),
				vfirst.getVersionAsTimestamp());

		System.out.println("Opened stream on latest version, expected: " + latestVersionName + " got: " + 
				vlatest.getBaseName());
		b = (byte)vlatest.read();
		System.out.println("Post-read: Opened stream on latest version, expected: " + latestVersionName + " got: " + 
				vlatest.getBaseName());
		Assert.assertEquals(vlatest.getBaseName(), latestVersionName);
		Assert.assertEquals(VersioningProfile.cutTerminalVersion(vlatest.getBaseName()).first(), defaultStreamName);
		Assert.assertEquals(VersioningProfile.getLastVersionAsLong(latestVersionName), 
				VersioningProfile.getLastVersionAsLong(vlatest.getBaseName()));
		Assert.assertEquals(VersioningProfile.getLastVersionAsTimestamp(latestVersionName), 
				VersioningProfile.getLastVersionAsTimestamp(vlatest.getBaseName()));
		Assert.assertEquals(VersioningProfile.getLastVersionAsTimestamp(latestVersionName),
				vlatest.getVersionAsTimestamp());
	}

	@Test
	public void testCCNVersionedInputStreamContentNameInt() throws Exception {
		// we can make a new handle; as long as we don't use the outputHandle it should work
		CCNVersionedInputStream vfirst = 
			new CCNVersionedInputStream(firstVersionName, ((4 > firstVersionMaxSegment) ? firstVersionMaxSegment : 4L), null);
		CCNVersionedInputStream vlatest = 
			new CCNVersionedInputStream(defaultStreamName, ((4 > latestVersionMaxSegment) ? latestVersionMaxSegment : 4L), null);
		testArgumentRunner(vfirst, vlatest);
	}

	@Test
	public void testCCNVersionedInputStreamContentObjectCCNLibrary() throws Exception {
		// we can make a new handle; as long as we don't use the outputHandle it should work
		ContentObject firstVersionBlock = inputHandle.get(firstVersionName, 1000);
		ContentObject latestVersionBlock = reader.getLatest(defaultStreamName, defaultStreamName.count(), 1000);
		CCNVersionedInputStream vfirst = new CCNVersionedInputStream(firstVersionBlock, inputHandle);
		CCNVersionedInputStream vlatest = new CCNVersionedInputStream(latestVersionBlock, inputHandle);
		testArgumentRunner(vfirst, vlatest);
	}

	@Test
	public void testReadByteArray() throws Exception {
		// Test other forms of read in superclass test.
		CCNVersionedInputStream vfirst = new CCNVersionedInputStream(firstVersionName, inputHandle);
		byte [] readDigest = readFile(vfirst, firstVersionLength);
		Assert.assertArrayEquals(firstVersionDigest, readDigest);
		CCNVersionedInputStream vmiddle = new CCNVersionedInputStream(middleVersionName, inputHandle);
		readDigest = readFile(vmiddle, middleVersionLength);
		Assert.assertArrayEquals(middleVersionDigest, readDigest);
		CCNVersionedInputStream vlatest = new CCNVersionedInputStream(defaultStreamName, inputHandle);
		readDigest = readFile(vlatest, latestVersionLength);
		Assert.assertArrayEquals(latestVersionDigest, readDigest);
	}

}
