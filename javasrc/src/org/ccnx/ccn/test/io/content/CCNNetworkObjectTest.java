package org.ccnx.ccn.test.io.content;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.util.Random;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.security.crypto.util.DigestHelper;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNVersionedInputStream;
import org.ccnx.ccn.io.content.CCNNetworkObject;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.PublisherID.PublisherType;
import org.ccnx.ccn.test.Flosser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


/**
 * Works. Currently very slow, as it's timing
 * out lots of blocks. End of stream markers will help with that, as
 * will potentially better binary ccnb decoding.
 * @author smetters
 *
 */
public class CCNNetworkObjectTest {
	
	static ContentName namespace;
	static ContentName stringObjName;
	static ContentName collectionObjName;
	static String prefix = "CollectionObject-";
	static ContentName [] ns = null;
	
	static public byte [] contenthash1 = new byte[32];
	static public byte [] contenthash2 = new byte[32];
	static public byte [] publisherid1 = new byte[32];
	static public byte [] publisherid2 = new byte[32];
	static PublisherID pubID1 = null;	
	static PublisherID pubID2 = null;
	static int NUM_LINKS = 15;
	static LinkAuthenticator [] las = new LinkAuthenticator[NUM_LINKS];
	static Link [] lrs = null;
	
	static Collection small1;
	static Collection small2;
	static Collection empty;
	static Collection big;
	static CCNHandle library;
	static String [] numbers = new String[]{"ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN"};
	
	static Level oldLevel;
	
	static Flosser flosser = null;
	
	static void setupNamespace(ContentName name) throws IOException {
		flosser.handleNamespace(name);
	}

	static void removeNamespace(ContentName name) throws IOException {
		flosser.stopMonitoringNamespace(name);
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		Log.setLevel(oldLevel);
		if (flosser != null) {
			flosser.stop();
			flosser = null;
		}
	}

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		System.out.println("Making stuff.");
		oldLevel = Log.getLevel();
		Log.setLevel(Level.FINE);
		
		library = CCNHandle.open();
		namespace = ContentName.fromNative("/parc/test/data/CCNNetworkObjectTest-" + + new Random().nextInt(10000));
		stringObjName = ContentName.fromNative(namespace, "StringObject");
		collectionObjName = ContentName.fromNative(namespace, "CollectionObject");
		
		ns = new ContentName[NUM_LINKS];
		for (int i=0; i < NUM_LINKS; ++i) {
			ns[i] = ContentName.fromNative(namespace, "Links", prefix+Integer.toString(i));
		}
		Arrays.fill(publisherid1, (byte)6);
		Arrays.fill(publisherid2, (byte)3);

		pubID1 = new PublisherID(publisherid1, PublisherType.KEY);
		pubID2 = new PublisherID(publisherid2, PublisherType.ISSUER_KEY);

		las[0] = new LinkAuthenticator(pubID1);
		las[1] = null;
		las[2] = new LinkAuthenticator(pubID2, null, null,
				SignedInfo.ContentType.DATA, contenthash1);
		las[3] = new LinkAuthenticator(pubID1, null, CCNTime.now(),
				null, contenthash1);
		
		for (int j=4; j < NUM_LINKS; ++j) {
			las[j] = new LinkAuthenticator(pubID2, null, CCNTime.now(), null, null);
 		}

		lrs = new Link[NUM_LINKS];
		for (int i=0; i < lrs.length; ++i) {
			lrs[i] = new Link(ns[i],las[i]);
		}
		
		empty = new Collection();
		small1 = new Collection();
		small2 = new Collection();
		for (int i=0; i < 5; ++i) {
			small1.add(lrs[i]);
			small2.add(lrs[i+5]);
		}
		big = new Collection();
		for (int i=0; i < NUM_LINKS; ++i) {
			big.add(lrs[i]);
		}
		
		flosser = new Flosser();
	}

	@Test
	public void testVersioning() throws Exception {
		// Testing problem of disappearing versions, inability to get latest. Use simpler
		// object than a collection.
		CCNHandle lput = CCNHandle.open();
		CCNHandle lget = CCNHandle.open();
		
		ContentName testName = ContentName.fromNative(stringObjName, "testVersioning");
		try {

			CCNStringObject so = new CCNStringObject(testName, "First value", lput);
			CCNStringObject ro = null;
			CCNStringObject ro2 = null;
			CCNStringObject ro3, ro4; // make each time, to get a new library.
			CCNTime soTime, srTime, sr2Time, sr3Time, sr4Time, so2Time;
			setupNamespace(testName);
			for (int i=0; i < numbers.length; ++i) {
				soTime = saveAndLog(numbers[i], so, null, numbers[i]);
				if (null == ro) {
					ro = new CCNStringObject(testName, lget);
					srTime = waitForDataAndLog(numbers[i], ro);
				} else {
					srTime = updateAndLog(numbers[i], ro, null);				
				}
				if (null == ro2) {
					ro2 = new CCNStringObject(testName, null);
					sr2Time = waitForDataAndLog(numbers[i], ro2);
				} else {
					sr2Time = updateAndLog(numbers[i], ro2, null);				
				}
				ro3 = new CCNStringObject(ro.getVersionedName(), null); // read specific version
				sr3Time = waitForDataAndLog("UpdateToROVersion", ro3);
				// Save a new version and pull old
				so2Time = saveAndLog(numbers[i] + "-Update", so, null, numbers[i] + "-Update");
				ro4 = new CCNStringObject(ro.getVersionedName(), null); // read specific version
				sr4Time = waitForDataAndLog("UpdateAnotherToROVersion", ro4);
				System.out.println("Update " + i + ": Times: " + soTime + " " + srTime + " " + sr2Time + " " + sr3Time + " different: " + so2Time);
				Assert.assertEquals("SaveTime doesn't match first read", soTime, srTime);
				Assert.assertEquals("SaveTime doesn't match second read", soTime, sr2Time);
				Assert.assertEquals("SaveTime doesn't match specific version read", soTime, sr3Time);
				Assert.assertFalse("UpdateTime isn't newer than read time", soTime.equals(so2Time));
				Assert.assertEquals("SaveTime doesn't match specific version read", soTime, sr4Time);
			}
		} finally {
			removeNamespace(testName);
		}
	}

	@Test
	public void testSaveToVersion() throws Exception {
		// Testing problem of disappearing versions, inability to get latest. Use simpler
		// object than a collection.
		CCNHandle lput = CCNHandle.open();
		CCNHandle lget = CCNHandle.open();
		ContentName testName = ContentName.fromNative(stringObjName, "testSaveToVersion");
		try {

			CCNTime desiredVersion = CCNTime.now();

			CCNStringObject so = new CCNStringObject(testName, "First value", lput);
			setupNamespace(testName);
			saveAndLog("SpecifiedVersion", so, desiredVersion, "Time: " + desiredVersion);
			Assert.assertEquals("Didn't write correct version", desiredVersion, so.getVersion());

			CCNStringObject ro = new CCNStringObject(testName, lget);
			ro.waitForData(); 
			Assert.assertEquals("Didn't read correct version", desiredVersion, ro.getVersion());
			ContentName versionName = ro.getVersionedName();

			saveAndLog("UpdatedVersion", so, null, "ReplacementData");
			updateAndLog("UpdatedData", ro, null);
			Assert.assertTrue("New version " + so.getVersion() + " should be later than old version " + desiredVersion, (desiredVersion.before(so.getVersion())));
			Assert.assertEquals("Didn't read correct version", so.getVersion(), ro.getVersion());

			CCNStringObject ro2 = new CCNStringObject(versionName, null);
			ro2.waitForData();
			Assert.assertEquals("Didn't read correct version", desiredVersion, ro2.getVersion());
		} finally {
			removeNamespace(testName);
		}
	}

	@Test
	public void testEmptySave() throws Exception {
		boolean caught = false;
		ContentName testName = ContentName.fromNative(stringObjName, "testEmptySave");
		try {
			CollectionObject emptycoll = 
				new CollectionObject(testName, (Collection)null, library);
			setupNamespace(testName);
			try {
				emptycoll.setData(small1); // set temporarily to non-null
				saveAndLog("Empty", emptycoll, null, null);
			} catch (InvalidObjectException iox) {
				// this is what we expect to happen
				caught = true;
			}
			Assert.assertTrue("Failed to produce expected exception.", caught);	
		} finally {
			removeNamespace(testName);
		}
	}

	@Test
	public void testStreamUpdate() throws Exception {

		ContentName testName = ContentName.fromNative(collectionObjName, "testStreamUpdate");
		try {
			CollectionObject testCollectionObject = new CollectionObject(testName, small1, CCNHandle.open());
			setupNamespace(testName);

			saveAndLog("testStreamUpdate", testCollectionObject, null, small1);
			System.out.println("testCollectionObject name: " + testCollectionObject.getVersionedName());

			CCNVersionedInputStream vis = new CCNVersionedInputStream(testCollectionObject.getVersionedName());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte [] buf = new byte[128];
			// Will incur a timeout
			while (!vis.eof()) {
				int read = vis.read(buf);
				if (read > 0)
					baos.write(buf, 0, read);
			}
			System.out.println("Read " + baos.toByteArray().length + " bytes, digest: " + 
					DigestHelper.printBytes(DigestHelper.digest(baos.toByteArray()), 16));

			Collection decodedData = new Collection();
			decodedData.decode(baos.toByteArray());
			System.out.println("Decoded collection data: " + decodedData);
			Assert.assertEquals("Decoding via stream fails to give expected result!", decodedData, small1);

			CCNVersionedInputStream vis2 = new CCNVersionedInputStream(testCollectionObject.getVersionedName());
			ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
			// Will incur a timeout
			while (!vis2.eof()) {
				int val = vis2.read();
				if (val < 0)
					break;
				baos2.write((byte)val);
			}
			System.out.println("Read " + baos2.toByteArray().length + " bytes, digest: " + 
					DigestHelper.printBytes(DigestHelper.digest(baos2.toByteArray()), 16));
			Assert.assertArrayEquals("Reading same object twice gets different results!", baos.toByteArray(), baos2.toByteArray());

			Collection decodedData2 = new Collection();
			decodedData2.decode(baos2.toByteArray());
			Assert.assertEquals("Decoding via stream byte read fails to give expected result!", decodedData2, small1);

			CCNVersionedInputStream vis3 = new CCNVersionedInputStream(testCollectionObject.getVersionedName());
			Collection decodedData3 = new Collection();
			decodedData3.decode(vis3);
			Assert.assertEquals("Decoding via stream full read fails to give expected result!", decodedData3, small1);
		} finally {
			removeNamespace(testName);
		}
	}
	
	@Test
	public void testVersionOrdering() throws Exception {
		ContentName testName = ContentName.fromNative(collectionObjName, "testVersionOrdering", "name1");
		ContentName testName2 = ContentName.fromNative(collectionObjName, "testVersionOrdering", "name2");

		try {

			CollectionObject c0 = new CollectionObject(testName, empty, library);
			setupNamespace(testName);
			CCNTime t0 = saveAndLog("Empty", c0, null, empty);

			CollectionObject c1 = new CollectionObject(testName2, small1, CCNHandle.open());
			setupNamespace(testName2);
			CCNTime t1 = saveAndLog("Small", c1, null, small1);
			Assert.assertTrue("First version should come before second", t0.before(t1));

			CollectionObject c2 = new CollectionObject(testName2, small1, null);
			CCNTime t2 = saveAndLog("Small2ndWrite", c2, null, small1);
			Assert.assertTrue("Third version should come after second", t1.before(t2));
			Assert.assertTrue(c2.contentEquals(c1));
			Assert.assertFalse(c2.equals(c1));
			Assert.assertTrue(VersioningProfile.isLaterVersionOf(c2.getVersionedName(), c1.getVersionedName()));
		} finally {
			removeNamespace(testName);
			removeNamespace(testName2);
		}
	}
	
	@Test
	public void testUpdateOtherName() throws Exception {
		ContentName testName = ContentName.fromNative(collectionObjName, "testUpdateOtherName", "name1");
		ContentName testName2 = ContentName.fromNative(collectionObjName, "testUpdateOtherName", "name2");
		try {

			CollectionObject c0 = new CollectionObject(testName, empty, library);
			setupNamespace(testName);
			CCNTime t0 = saveAndLog("Empty", c0, null, empty);

			CollectionObject c1 = new CollectionObject(testName2, small1, CCNHandle.open());
			setupNamespace(testName2);
			CCNTime t1 = saveAndLog("Small", c1, null, small1);
			Assert.assertTrue("First version should come before second", t0.before(t1));

			CollectionObject c2 = new CollectionObject(testName2, small1, null);
			CCNTime t2 = saveAndLog("Small2ndWrite", c2, null, small1);
			Assert.assertTrue("Third version should come after second", t1.before(t2));
			Assert.assertTrue(c2.contentEquals(c1));
			Assert.assertFalse(c2.equals(c1));

			CCNTime t3 = updateAndLog(c0.getVersionedName().toString(), c0, testName2);
			Assert.assertTrue(VersioningProfile.isVersionOf(c0.getVersionedName(), testName2));
			Assert.assertEquals(t3, t2);
			Assert.assertTrue(c0.contentEquals(c2));

			t3 = updateAndLog(c0.getVersionedName().toString(), c0, c1.getVersionedName());
			Assert.assertTrue(VersioningProfile.isVersionOf(c0.getVersionedName(), testName2));
			Assert.assertEquals(t3, t1);
			Assert.assertTrue(c0.contentEquals(c1));	
		} finally {
			removeNamespace(testName);
			removeNamespace(testName2);
		}

	}

	
	@Test
	public void testSaveAsGone() throws Exception {
		ContentName testName = ContentName.fromNative(collectionObjName, "testSaveAsGone");

		try {
			CollectionObject c0 = new CollectionObject(testName, empty, library);
			setupNamespace(testName); // this sends the interest, doing it after the object gives it
						// a chance to catch it.
			
			
			CCNTime t0 = saveAsGoneAndLog("Gone", c0);
			Assert.assertTrue("Should be gone", c0.isGone());
			ContentName goneVersionName = c0.getVersionedName();
			
			CCNTime t1 = saveAndLog("NotGone", c0, null, small1);
			Assert.assertFalse("Should not be gone", c0.isGone());
			Assert.assertTrue(t1.after(t0));
			Log.info("T2");

			CollectionObject c1 = new CollectionObject(testName, CCNHandle.open());
			CCNTime t2 = waitForDataAndLog(testName.toString(), c1);
			Assert.assertFalse("Read back should not be gone", c1.isGone());
			Assert.assertEquals(t2, t1);
			Log.info("T3");

			CCNTime t3 = updateAndLog(goneVersionName.toString(), c1, goneVersionName);
			Assert.assertTrue(VersioningProfile.isVersionOf(c1.getVersionedName(), testName));
			Assert.assertEquals(t3, t0);
			Assert.assertTrue("Read back should be gone.", c1.isGone());
			Log.info("T4");

			t0 = saveAsGoneAndLog("GoneAgain", c0);
			Assert.assertTrue("Should be gone", c0.isGone());
			CollectionObject c2 = new CollectionObject(testName, CCNHandle.open());
			CCNTime t4 = waitForDataAndLog(testName.toString(), c2);
			Assert.assertTrue("Read back of " + c0.getVersionedName() + " should be gone, got " + c2.getVersionedName(), c2.isGone());
			Assert.assertEquals(t4, t0);
			Log.info("T5");

		} finally {
			removeNamespace(testName);
		}

	}
	
	public <T> CCNTime saveAndLog(String name, CCNNetworkObject<T> ecd, CCNTime version, T data) throws XMLStreamException, IOException {
		CCNTime oldVersion = ecd.getVersion();
		ecd.save(version, data);
		Log.info("Saved " + name + ": " + ecd.getVersionedName() + " (" + ecd.getVersion() + ", updated from " + oldVersion + ")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getVersion();
	}
	
	public <T> CCNTime saveAsGoneAndLog(String name, CCNNetworkObject<T> ecd) throws XMLStreamException, IOException {
		CCNTime oldVersion = ecd.getVersion();
		ecd.saveAsGone();
		Log.info("Saved " + name + ": " + ecd.getVersionedName() + " (" + ecd.getVersion() + ", updated from " + oldVersion + ")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getVersion();
	}
	
	public CCNTime waitForDataAndLog(String name, CCNNetworkObject<?> ecd) throws XMLStreamException, IOException {
		ecd.waitForData();
		Log.info("Initial read " + name + ", name: " + ecd.getVersionedName() + " (" + ecd.getVersion() +")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getVersion();
	}

	public CCNTime updateAndLog(String name, CCNNetworkObject<?> ecd, ContentName updateName) throws XMLStreamException, IOException {
		if (((null == updateName) && ecd.update()) || (ecd.update(updateName, null)))
			Log.info("Updated " + name + ", to name: " + ecd.getVersionedName() + " (" + ecd.getVersion() +")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		else 
			Log.info("No update found for " + name + ((null != updateName) ? (" at name " + updateName) : "") + ", still: " + ecd.getVersionedName() + " (" + ecd.getVersion() +")" +  " gone? " + ecd.isGone() + " data: " + ecd);
		return ecd.getVersion();
	}

}
