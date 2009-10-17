package org.ccnx.ccn.test.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.CCNVersionedOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.test.CCNTestHelper;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class CCNVersionedOutputStreamTest implements CCNFilterListener {
	
	static CCNTestHelper testHelper = new CCNTestHelper(CCNVersionedOutputStreamTest.class);
	static CCNHandle readHandle;
	static CCNHandle writeHandle;
	static byte [] writeDigest;
	static int BUF_SIZE = 2048;
	static int FILE_SIZE = 65556;
	static Writer writer;
	
	public static class Writer extends Thread {
		protected static Random random = new Random();
		
		protected OutputStream _stream;
		protected int _fileLength;
		protected boolean _done = false;
		
		public Writer(OutputStream stream, int fileLength) {
			_stream = stream;
			_fileLength = fileLength;
		}

		public boolean isDone() { return _done; }
		
		@Override
		public void run() {
			try {
				synchronized (this) {
					writeDigest = writeRandomFile(_stream, _fileLength, random);
					Log.info("Finished writing file of {0} bytes, digest {1}.", _fileLength, DataUtils.printHexBytes(writeDigest));
					_done = true;
					this.notifyAll();
				}
			} catch (IOException e) {
				Log.severe("Exception writing random file: " + e.getClass().getName() + ": " + e.getMessage());
				Log.logStackTrace(Level.SEVERE, e);
				Assert.fail("Exception in writeRandomFile: " + e);
			}
		}
	}
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		try {
			readHandle = CCNHandle.open();
			writeHandle = CCNHandle.open();
		} catch (Exception e) {
			Log.severe("Exception in setUpBeforeClass: {0}: {1}", e.getClass().getName(), e);
			throw e;
		}
	}

	@Test
	public void testAddOutstandingInterest() throws Exception {
		
		// Let's express an Interest in some data, and see if the network managers can
		// handle the threading for us...
		ContentName streamName = ContentName.fromNative(testHelper.getTestNamespace("testAddOutstandingInterest"), "testFile.bin");
	
		writeHandle.registerFilter(streamName, this);
		// Get the latest version when no versions exist. 
		CCNVersionedInputStream vis = new CCNVersionedInputStream(streamName, readHandle);
		byte [] resultDigest = readFile(vis);
		Log.info("Finished reading, read result {0}", DataUtils.printHexBytes(resultDigest));
		if (!writer.isDone()) {
			synchronized(writer) {
				writer.wait();
			}
		}
		Log.info("Finished writing, read result {0}, write result {1}", DataUtils.printHexBytes(resultDigest), DataUtils.printHexBytes(writeDigest));
		Assert.assertArrayEquals(resultDigest, writeDigest);
	}
	
	public static byte [] readFile(InputStream inputStream) throws IOException, XMLStreamException {
		
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
		while (true) {
			read = dis.read(bytes);
			if (read < 0) {
				System.out.println("EOF read at " + elapsed + " bytes.");
				break;
			} else if (read == 0) {
				System.out.println("0 bytes read at " + elapsed + " bytes.");
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					
				}
			}
			elapsed += read;
			System.out.println(" read " + elapsed + " bytes.");
		}
		return dis.getMessageDigest().digest();
	}
	

	public static byte [] writeRandomFile(OutputStream stream, int fileLength, Random randBytes) throws IOException {
		DigestOutputStream digestStreamWrapper = null;
		try {
			digestStreamWrapper = new DigestOutputStream(stream, MessageDigest.getInstance("SHA1"));
		} catch (NoSuchAlgorithmException e) {
			Log.severe("No SHA1 available!");
			Assert.fail("No SHA1 available!");
		}
		byte [] bytes = new byte[BUF_SIZE];
		int elapsed = 0;
		int nextBufSize = 0;
		final double probFlush = .3;
		
		while (elapsed < fileLength) {
			nextBufSize = ((fileLength - elapsed) > BUF_SIZE) ? BUF_SIZE : (fileLength - elapsed);
			randBytes.nextBytes(bytes);
			digestStreamWrapper.write(bytes, 0, nextBufSize);
			elapsed += nextBufSize;
			if (randBytes.nextDouble() < probFlush) {
				System.out.println("Flushing buffers, have written " + elapsed + " bytes out of " + fileLength);
				digestStreamWrapper.flush();
			}
		}
		digestStreamWrapper.close();
		return digestStreamWrapper.getMessageDigest().digest();
	}
	
	@Override
	public int handleInterests(ArrayList<Interest> interests) {
		Interest interest = interests.get(0);
		// we only deal with the first interest, at least for now
		if (null != writer) {
			Log.info("handleInterests: already writing stream, ignoring interest {0} of set of {1}", interest, interests.size());
			return 0;
		}
		Log.info("handleInterests got first interest {0} out of a set of {1}", interest, interests.size());
		CCNVersionedOutputStream vos = null;
		try {
			vos = new CCNVersionedOutputStream(interest.name(), writeHandle);
		} catch (IOException e) {
			Log.severe("Exception in creating output stream: {0}", e);
			Log.logStackTrace(Level.SEVERE, e);
			Assert.fail("Exception creating output stream " + e);
		}
		vos.addOutstandingInterest(interest);
		writer = new Writer(vos, FILE_SIZE);
		writer.run();
		return 1;
	}

}
