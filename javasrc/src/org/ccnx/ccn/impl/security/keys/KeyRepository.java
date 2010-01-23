/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.security.keys;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.TrustManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowServer;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.repo.RepositoryFlowControl;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.DataUtils.Tuple;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.KeyName;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.KeyLocator.KeyLocatorType;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * This class performs the following:
 * - manages published public keys.
 * - answers CCN interestes for these public keys.
 * - allow the caller to look for public keys: retrieve the keys from cache or CCN 
 * 
 * This class is used by the initial KeyManager bootstrapping code. As such,
 * it has to be very careful not to introduce a circular dependency -- to rely
 * on parts of the network stack that need that boostrapping to be complete in
 * order to work. At the same time, we'd like to not have to reimplement segmentation,
 * etc, in order to cache keys; we'd like to be able to use those parts of
 * the library. So we allow the KeyRepository to have a CCNHandle, we can use
 * all of the library functionality to write keys once that handle is sufficiently
 * initialized.
 */

public class KeyRepository {

	// Stop logging to key cache by default.
	protected static final boolean _DEBUG = false;

	protected KeyManager _keyManager = null;
	protected CCNHandle _handle = null;
	protected boolean _ourHandle = false;
	protected CCNFlowServer _keyServer = null;
	
	// Reference count in case we are shared. 
	protected int _refCount = 0;

	protected HashMap<ContentName, PublicKeyObject> _keyMap = new HashMap<ContentName, PublicKeyObject>();
	protected HashMap<PublisherPublicKeyDigest, ArrayList<ContentName>> _idMap = new HashMap<PublisherPublicKeyDigest, ArrayList<ContentName>>();
	protected HashMap<PublisherPublicKeyDigest, PublicKey> _rawKeyMap = new HashMap<PublisherPublicKeyDigest, PublicKey>();
	protected HashMap<PublisherPublicKeyDigest, ArrayList<Certificate>> _rawCertificateMap = new HashMap<PublisherPublicKeyDigest, ArrayList<Certificate>>();
	protected HashMap<PublisherPublicKeyDigest, CCNTime> _rawVersionMap = new HashMap<PublisherPublicKeyDigest, CCNTime>();

	/** 
	 * Constructor. Doesn't actually use the KeyManager right away;
	 * doesn't attempt network operations until initializeKeyServer
	 * is called (usually by publishKey).
	 */
	public KeyRepository(KeyManager keyManager) {
		_keyManager = keyManager;
	}

	/**
	 * Constructor; uses existing handle.
	 * @param handle
	 */
	public KeyRepository(CCNHandle handle) {
		_handle = handle;
		_keyManager = handle.keyManager();
	}

	public CCNHandle handle() throws IOException { 
		if (null == _handle) {
			synchronized(this) {
				if (null == _handle) {
					_handle = CCNHandle.open(_keyManager); // maintain our own connection to the agent, so
					_ourHandle = true; // we made it, we own it
				}
			}
		}
		return _handle; 
	}

	public synchronized void initializeKeyServer() throws IOException {
		if (keyServerIsInitialized()) {
			return;
		}
		// everyone can ask us for keys even if we have no repository
		// make a buffered server to return key data
		_keyServer = new CCNFlowServer(null, true, handle());
	}

	public synchronized boolean keyServerIsInitialized() {
		return (null != _keyServer);
	}

	/**
	 * Published a signed record for this key if one doesn't exist.
	 * (if it does exist, pulls it at least to our ccnd, and optionally
	 * makes it available).
	 * @param keyName the key's content name; if it contains a version as its terminal
	 * 		element the key will be published under that version. (Note that this is not
	 * 		standard behavior for savable network content, which needs its version explicitly
	 * 		set.)
	 * @param keyToPublish the public key to publish
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with; uses the default
	 * 	key locator
	 * @return void
	 * @throws IOException
	 */
	public void publishKey(ContentName keyName, PublisherPublicKeyDigest keyToPublish, 
						PublisherPublicKeyDigest signingKeyID) 
	throws IOException {
		publishKey(keyName, keyToPublish, signingKeyID, null);
	}

	/**
	 * Publish a signed record for this key if one doesn't exist.
	 * (if it does exist, pulls it at least to our ccnd, and optionally
	 * makes it available). (TODO: decide what to do if it's published by someone
	 * else... need another option for that.)
	 * @param keyName the key's content name. Will add a version when saving if it doesn't
	 * 	have one already. If it does have a version, will use that one (see below for effect
	 * 	of version on the key locator). (Note that this is not
	 * 		standard behavior for savable network content, which needs its version explicitly
	 * 		set.)
	 * @param keyToPublish the public key to publish
	 * @param keyID the publisher id
	 * @param signingKeyID the key id of the key pair to sign with
	 * @param signingKeyLocator the key locator to use if we save this key (if it is not already published).
	 * 	If not specified, we look for the default locator for the signing key. If there is none,
	 * 	and we are signing with the same key we are publishing, we build a
	 * 	self-referential key locator, using the name passed in (versioned or not).
	 * @return void
	 * @throws IOException
	 */
	public void publishKey(ContentName keyName, PublisherPublicKeyDigest keyToPublish,
						   PublisherPublicKeyDigest signingKeyID, KeyLocator signingKeyLocator) 
	throws IOException {

		// Set up key server if it hasn't been set up already
		initializeKeyServer();

		// See if we can pull something acceptable for this key at this name.
		// Use same code path for default key retrieval as getPublicKey, so that we can manage
		// version handling in a single place.
		KeyLocator targetKeyLocator = new KeyLocator(keyName);
		Log.info("publishKey: publishing key {0}, first retrieving using {1}", keyName, targetKeyLocator);
		
		PublicKey theKey = getPublicKey(keyToPublish, targetKeyLocator, SystemConfiguration.SHORT_TIMEOUT);
		PublicKeyObject keyObject = null;
		if (null != theKey) {
			keyObject = retrieve(keyToPublish);
		} 
		if (null == keyObject) {
			// This might have been one of our keys, so we got it straight from
			// cache; try to ensure it's not on the network. Might eventually
			// want to check publisher as well
			keyObject = getPublicKeyObject(keyToPublish, targetKeyLocator, SystemConfiguration.SHORT_TIMEOUT);
			if ((null == theKey) && (null != keyObject) && (keyObject.available())) {
				theKey = keyObject.publicKey();
			}
		}
		// Now, finally; it's not published, so make an object to write it
		// with. We've already tried to pull it, so don't try here. Will
		// set publisher info below.
		CCNTime keyVersion = null; // do we force a version?
		
		if (null == keyObject) {
			// Here is where we get tricky. We might really want the key to be of a particular
			// version. In general, as we use the network objects to write versioned versioned stuff,
			// we might not be able to take the last component of a name, if versioned, as the version
			// to use to save -- might really want <name>/<version1>/<version2>. So unless we want to 
			// make that impossible to achieve, we need to not have the network objects take the 
			// name <name>/<version1> and save to <version1> (though they read from <version1> just
			// fine given the same). You always want to save to a new version, unless someone tells you
			// something different from the outside. 
			// Come up with a contorted option. If you want to publish <version>/<version> stuff, you
			// need to pass in the second version...
			Tuple<ContentName, byte []> nameAndVersion = VersioningProfile.cutTerminalVersion(keyName);
			
			keyObject = new PublicKeyObject(nameAndVersion.first(), theKey, null, null, _keyServer);
			if (null != nameAndVersion.second()) {
				keyVersion = VersioningProfile.getVersionComponentAsTimestamp(nameAndVersion.second());
			}
			Log.info("publishKey: key not previously published, making new key object {0} with version {1} displayed as {2}", 
							keyObject.getVersionedName(), keyVersion, 
							((null != nameAndVersion.second()) ? ContentName.componentPrintURI(nameAndVersion.second()) : "<no version>"));
		}

		if (!keyObject.isSaved() || (!keyObject.publicKeyDigest().equals(keyToPublish))) {
			// Eventually may want to find something already published and link to it, but be simple here.

			// Need a key locator to stick in data entry for
			// locator. Could use key itself, but then would have
			// key both in the content for this item and in the
			// key locator, which is redundant. Use naming form
			// that allows for self-referential key names -- the
			// CCN equivalent of a "self-signed cert". Means that
			// we will refer to only the base key name and the publisher ID.
			if (null == signingKeyID) {
				signingKeyID = handle().keyManager().getDefaultKeyID();
			}

			if (null == signingKeyLocator) {
				KeyLocator constructedLocator = handle().keyManager().getKeyLocator(signingKeyID);
				if ((constructedLocator.type() == KeyLocatorType.KEY) && 
					(signingKeyID.equals(keyToPublish))) {
						// Make a self-referential key locator. For now do not include the
						// version.
					constructedLocator = new KeyLocator(new KeyName(keyName, signingKeyID));
				}
				signingKeyLocator = constructedLocator;
			}

			keyObject.setOurPublisherInformation(signingKeyID, signingKeyLocator);
			// nobody's written it where we can find it fast enough.
			// theKey will be retrieved from cache if not stored on network
			keyObject.setData(theKey);

			if (!keyObject.save(keyVersion)) {
				Log.info("Not saving key when we thought we needed to: desired key value {0}, have key value {1}, " +
						keyToPublish, new PublisherPublicKeyDigest(keyObject.publicKey()));
			} else {
				Log.info("Published key {0} to name {1} with key locator {2}.", keyToPublish, keyObject.getVersionedName(), signingKeyLocator);
			}
		} else {
			Log.info("Retrieved existing key object {0}, whose key locator is {1}.", keyObject.getVersionedName(), keyObject.getPublisherKeyLocator());
		}
		remember(keyObject);
	}

	/**
	 * Overly duplicated code. TODO condense with method above.
	 */
	public void publishKey(ContentName keyName, PublicKey theKey, PublisherPublicKeyDigest signingKeyID, KeyLocator keyLocator) 
	throws IOException {

		// Set up key server if it hasn't been set up already
		initializeKeyServer();

		// See if we can pull something acceptable for this key at this name.
		// Use same code path for default key retrieval as getPublicKey, so that we can manage
		// version handling in a single place.
		if (null == theKey) {
			theKey = handle().keyManager().getDefaultPublicKey();
		}
		PublisherPublicKeyDigest keyToPublish = new PublisherPublicKeyDigest(theKey);
		
		PublicKeyObject keyObject = null;
		if (null != theKey) {
			keyObject = retrieve(keyToPublish);
		} 
		if (null == keyObject) {
			keyObject = new PublicKeyObject(keyName, signingKeyID, _keyServer);
		}

		if (!keyObject.available() || (!keyObject.publicKeyDigest().equals(keyToPublish))) {
			// Eventually may want to find something already published and link to it, but be simple here.

			// Need a key locator to stick in data entry for
			// locator. Could use key itself, but then would have
			// key both in the content for this item and in the
			// key locator, which is redundant. Use naming form
			// that allows for self-referential key names -- the
			// CCN equivalent of a "self-signed cert". Means that
			// we will refer to only the base key name and the publisher ID.
			if (null == signingKeyID) {
				signingKeyID = handle().keyManager().getDefaultKeyID();
			}

			if (null == keyLocator) {
				KeyLocator constructedLocator = handle().keyManager().getKeyLocator(signingKeyID);
				if ((constructedLocator.type() == KeyLocatorType.KEY) && 
					(signingKeyID.equals(keyToPublish))) {
						// Make a self-referential key locator. For now do not include the
						// version.
					constructedLocator = new KeyLocator(new KeyName(keyName, signingKeyID));
				}
				keyLocator = constructedLocator;
			}

			keyObject.setOurPublisherInformation(signingKeyID, keyLocator);
			// nobody's written it where we can find it fast enough.
			// theKey will be retrieved from cache if not stored on network
			keyObject.setData(theKey);

			if (!keyObject.save()) {
				Log.info("Not saving key when we thought we needed to: desired key value {0}, have key value {1}, " +
						keyToPublish, new PublisherPublicKeyDigest(keyObject.publicKey()));
			} else {
				Log.info("Published key {0} to name {1}", keyToPublish, keyObject.getVersionedName());
			}
		} else {
			Log.info("Retrieved existing key object {0}, whose key locator is {1}.", keyObject.getVersionedName(), keyObject.getPublisherKeyLocator());
		}
		remember(keyObject);
	}

	/**
	 * TODO DKS make sure this works if last component of key name is potentially
	 * a version (using objects to write public keys)
	 * Publish my public key to repository
	 * @param keyName content name of the public key
	 * @param keyToPublish public key digest
	 * @param handle handle for ccn
	 * @throws IOException
	 * @throws InvalidKeyException
	 * @throws ConfigurationException
	 */
	public void publishKeyToRepository(ContentName keyName, 
			PublisherPublicKeyDigest keyToPublish) throws InvalidKeyException, IOException, ConfigurationException {


		PublicKey key = getPublicKeyFromCache(keyToPublish);
		if (null == key) {
			throw new InvalidKeyException("Cannot retrieive key " + keyToPublish);
		}

		// HACK - want to use repo confirmation protocol to make sure data makes it to a repo
		// even if it doesn't come from us. Problem is, we may have already written it, and don't
		// want to write a brand new version of identical data. If we try to publish it under
		// the same (unversioned) name, the repository may get some of the data from the ccnd
		// cache, which will cause us to think it hasn't been written. So for the moment, we use the
		// name enumeration protocol to determine whether this key has been written to a repository
		// already.
		// This works because the last explicit name component of the key is its publisherID. 
		// We then use a further trick, just calling startWrite on the key, to get the repo
		// to read it -- not from here, but from the key server embedded in the KeyManager.
		EnumeratedNameList enl = new EnumeratedNameList(keyName.parent(), handle());
		enl.waitForChildren(500); // have to time out, may be nothing there.
		enl.stopEnumerating();
		if (enl.hasChildren()) {
			Log.info("Looking for children of {0} matching {1}.", keyName.parent(), keyName);
			for (ContentName name: enl.getChildren()) {
				Log.info("Child: {0}", name);
			}
		}
		if (!enl.hasChildren() || !enl.hasChild(keyName.lastComponent())) {
			RepositoryFlowControl rfc = new RepositoryFlowControl(keyName, handle());
			rfc.startWrite(keyName, Shape.STREAM);
			Log.info("Key {0} published to repository.", keyName);
		} else {
			Log.info("Key {0} already published to repository, not re-publishing.", keyName);
		}
	}

	/**
	 * Remember a public key and the corresponding key object.
	 * @param theKey public key to remember
	 * @param keyObject key Object to remember
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ErrorStateException 
	 */
	public void remember(PublicKeyObject theKey) throws ContentNotReadyException, ContentGoneException, ErrorStateException, IOException {

		_keyMap.put(theKey.getVersionedName(), theKey);
		PublisherPublicKeyDigest id = theKey.publicKeyDigest();
		rememberContentName(id, theKey.getVersionedName());
		_rawKeyMap.put(id, theKey.publicKey());
		_rawVersionMap.put(id, theKey.getVersion());
		if (_DEBUG) {
			recordKeyToFile(theKey);
		}
	}
	
	protected void rememberContentName(PublisherPublicKeyDigest id, ContentName name) {
		synchronized(_idMap) {

			ArrayList<ContentName> nameList = _idMap.get(id);
			if (null == nameList) {
				nameList = new ArrayList<ContentName>();
				_idMap.put(id, nameList);
			}
			nameList.add(name);
		}
	}

	/**
	 * Remember a public key 
	 * @param theKey public key to remember
	 */
	public void remember(PublicKey theKey, CCNTime version) {
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(theKey);
		_rawKeyMap.put(keyDigest, theKey);
		if (null != version) {
			_rawVersionMap.put(keyDigest, version);
		}
	}

	/**
	 * Remember a certificate.
	 * @param theCertificate the certificate to remember
	 */
	public void remember(Certificate theCertificate, CCNTime version) {
		PublisherPublicKeyDigest keyDigest = new PublisherPublicKeyDigest(theCertificate.getPublicKey());
		rememberCertificate(keyDigest, theCertificate);
		_rawKeyMap.put(keyDigest, theCertificate.getPublicKey());
		if (null != version) {
			_rawVersionMap.put(keyDigest, version);
		}
	}

	protected void rememberCertificate(PublisherPublicKeyDigest id, Certificate certificate) {
		synchronized(_rawCertificateMap) {

			ArrayList<Certificate> certificateList = _rawCertificateMap.get(id);
			if (null == certificateList) {
				certificateList = new ArrayList<Certificate>();
				_rawCertificateMap.put(id, certificateList);
			}
			certificateList.add(certificate);
		}
	}

	/**
	 * Write encoded key to file for debugging purposes.
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ErrorStateException 
	 */
	protected void recordKeyToFile(PublicKeyObject keyObject) throws ContentNotReadyException, ContentGoneException, ErrorStateException {
		File keyDir = new File(UserConfiguration.keyRepositoryDirectory());
		if (!keyDir.exists()) {
			if (!keyDir.mkdirs()) {
				Log.warning("recordKeyToFile: Cannot create user CCN key repository directory: " + keyDir.getAbsolutePath());
				return;
			}
		}
		PublisherPublicKeyDigest id = keyObject.publicKeyDigest();

		File keyFile  = new File(keyDir, KeyProfile.keyIDToNameComponentAsString(keyObject.publicKeyDigest()));

		if (keyFile.exists()) {
			Log.info("Already stored key " + id.toString() + " to file.");
			// return; // temporarily store it anyway, to overwrite old-format data.
		}

		try {
			FileOutputStream fos = new FileOutputStream(keyFile);
			fos.write(keyObject.publicKey().getEncoded());
			fos.close();
		} catch (Exception e) {
			Log.info("recordKeyToFile: cannot record key: " + id.toString() + " to file " + keyFile.getAbsolutePath() + " error: " + e.getClass().getName() + ": " + e.getMessage());
			return;
		}
		Log.info("Logged key " + id.toString() + " to file: " + keyFile.getAbsolutePath());
	}

	/**
	 * Retrieve the public key from CCN given a key digest and a key locator
	 * the function blocks and waits for the public key until a certain timeout.
	 * As a side effect, caches network storage information for this key, which can
	 * be obtained using retrieve();.
	 * @param desiredKeyID the digest of the desired public key.
	 * @param locator locator for the key
	 * @param timeout timeout value
	 * @throws IOException 
	 */
	public PublicKey getPublicKey(PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, long timeout) throws IOException {

		// Look for it in our cache first.
		PublicKey publicKey = getPublicKeyFromCache(desiredKeyID);
		if (null != publicKey) {
			return publicKey;
		}
		
		if (null == locator) {
			Log.warning("Cannot retrieve key -- no key locator for key {0}", desiredKeyID);
			throw new IOException("Cannot retrieve key -- no key locator for key " + desiredKeyID + ".");
		}

		if (locator.type() != KeyLocator.KeyLocatorType.NAME) {
			Log.info("Repository looking up a key that is contained in the locator...");
			if (locator.type() == KeyLocator.KeyLocatorType.KEY) {
				PublicKey key = locator.key();
				remember(key, null);
				return key;
			} else if (locator.type() == KeyLocator.KeyLocatorType.CERTIFICATE) {
				Certificate certificate = locator.certificate();
				PublicKey key = certificate.getPublicKey();
				remember(certificate, null);
				return key;
			}
		} else {
			PublicKeyObject publicKeyObject = getPublicKeyObject(desiredKeyID, locator, timeout);
			if (null == publicKeyObject) {
				Log.info("Could not retrieve key " + desiredKeyID + " from network with locator " + locator + "!");
			} else {
				Log.info("Retrieved key " + desiredKeyID + " from network with locator " + locator + "!");
				return publicKeyObject.publicKey();
			}
		}
		return null;
	}
	
	public PublicKeyObject getPublicKeyObject(PublisherPublicKeyDigest desiredKeyID, KeyLocator locator, long timeout) throws IOException {
		// take code from #BasicKeyManager.getKey, to validate more complex publisher constraints
		PublicKeyObject theKey = retrieve(locator.name().name(), locator.name().publisher());
		if ((null != theKey) && (theKey.available())) {
			Log.info("retrieved key {0} from cache.", locator.name().name());
			return theKey;
		}

		// How many pieces of bad content do we wade through?
		final int ITERATION_LIMIT = 5;
		// how many times do we time out get? Try 2 just in case we drop one.
		final int TIMEOUT_ITERATION_LIMIT = 2;

		PublicKey publicKey = null;
		
		Interest keyInterest = new Interest(locator.name().name(), locator.name().publisher());
		// we could have from 1 (content digest only) to 3 (version, segment, content digest) 
		// additional name components.
		keyInterest.minSuffixComponents(1);
		keyInterest.maxSuffixComponents(3);

		ContentObject retrievedContent = null;
		int iterationCount = 0;
		int timeoutCount = 0; // be super-agressive about pulling keys for now.
		IOException lastException = null;

		while ((null == publicKey) && (iterationCount < ITERATION_LIMIT)) {
			//  it would be really good to know how many additional name components to expect...
			while ((null == retrievedContent) && (timeoutCount < TIMEOUT_ITERATION_LIMIT)) {
				try {
					Log.fine("Trying network retrieval of key: " + keyInterest.name());
					// use more aggressive high-level get
					retrievedContent = handle().get(keyInterest, timeout);
				} catch (IOException e) {
					Log.warning("IOException attempting to retrieve key: " + keyInterest.name() + ": " + e.getMessage());
					Log.warningStackTrace(e);
					lastException = e;
					// go around again
				}
				if (null != retrievedContent) {
					Log.info("Retrieved key {0} using locator {1}.", desiredKeyID, locator);
					break;
				}
				timeoutCount++;
			}
			if (null == retrievedContent) {
				Log.info("No data returned when we attempted to retrieve key using interest {0}, timeout " + timeout + " exception : " + ((null == lastException) ? "none" : lastException.getMessage()), keyInterest);
				if (null != lastException) {
					throw lastException;
				}
				break;
			}
			if (retrievedContent.signedInfo().getType().equals(ContentType.KEY)) {
				theKey = new PublicKeyObject(retrievedContent, handle());
				if ((null != theKey) && (theKey.available())) {
					if ((null != desiredKeyID) && (!theKey.publicKeyDigest().equals(desiredKeyID))) {
						Log.fine("Got key at expected name {0}, but it wasn't the right key, wanted {0}, got {1}", 
								desiredKeyID, theKey.publicKeyDigest());
					} else {
						// either we don't have a preferred key ID, or we matched
						Log.info("Retrieved public key using name: " + locator.name().name());
						// TODO make a key object instead of just retrieving
						// content, use it to decode
						remember(theKey);
						return theKey;
					}
				} else {
					Log.severe("Decoded key at name {0} without error, but result was null!", retrievedContent.name());
					throw new IOException("Decoded key at name " + retrievedContent.name() + " without error, but result was null!");
				}
			} else {
				Log.warning("Retrieved an object when looking for key " + locator.name().name() + " at " + retrievedContent.name() + ", but type is " + retrievedContent.signedInfo().getTypeName());
			}
			// TODO -- not sure this is exactly right, but a start...
			Exclude currentExclude = keyInterest.exclude();
			currentExclude.add(new byte [][]{retrievedContent.digest()});
			keyInterest.exclude(currentExclude);
			iterationCount++;
		}
		return null;
	}
	
	/**
	 * Retrieve the public key from cache given a key digest 
	 * @param desiredKeyID the digest of the desired public key.
	 */
	public PublicKey getPublicKeyFromCache(PublisherPublicKeyDigest desiredKeyID) {
		PublicKey theKey = _rawKeyMap.get(desiredKeyID);
		if (null == theKey) {
			Certificate theCertificate = _rawCertificateMap.get(desiredKeyID).get(0);
			if (null != theCertificate) {
				theKey = theCertificate.getPublicKey();
			}
		}
		return theKey;
	}

	public CCNTime getPublicKeyVersionFromCache(PublisherPublicKeyDigest desiredKeyID) {
		return _rawVersionMap.get(desiredKeyID);
	}

	/**
	 * Retrieve key object from cache given key name 
	 * @param keyName key digest
	 */
	public PublicKeyObject retrieve(PublisherPublicKeyDigest keyID) {
		if (!_idMap.containsKey(keyID)) {
			return null;
		}
		ContentName name = _idMap.get(keyID).get(0);
		if (null != name) {
			return _keyMap.get(name);
		}		
		return null;
	}

	/**
	 * Retrieve key object from cache given content name and publisher id
	 * check if the retrieved content has the expected publisher id 
	 * @param name contentname of the key
	 * @param publisherID publisher id
	 * @throws IOException 
	 */
	public PublicKeyObject retrieve(ContentName name, PublisherID publisherID) throws IOException {
		PublicKeyObject result = _keyMap.get(name);
		if (null != result) {
			if (null != publisherID) {
				if (TrustManager.getTrustManager().matchesRole(
						publisherID,
						result.getContentPublisher())) {
					return result;
				}
			}
		}
		return null;
	}
	
	/**
	 * Close our handle, set it to null. Currently will recreate it automatically
	 * when we next need it. This might be a little too automatic...
	 */
	public synchronized void close() {
		if (null != _handle) {
			if (!_ourHandle) {
				Log.info("KeyRepository: asked to close a handle that we didn't create. Should we? Could be used elsewhere.");
			}
			_handle.close();
			_handle = null;
		}
	}
}
