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

package org.ccnx.ccn.profiles.security.access.group;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.support.ByteArrayCompare;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.LinkAuthenticator;
import org.ccnx.ccn.io.content.WrappedKey;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.io.content.WrappedKey.WrappedKeyObject;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.profiles.security.access.AccessDeniedException;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;


/**
 * A key directory holds a set of keys, wrapped under different
 * target keys. It is implemented as a set of wrapped key objects
 * all stored in one directory. Wrapped key objects are typically short
 * and only need one segment. The directory the keys are stored in
 * is prefixed by a version, to allow the contents to evolve. In addition
 * some potential supporting information pointing to previous
 * or subsequent versions of this key is kept. A particular wrapped key
 * entry's name would look like:
 *
 * <pre>.../v123/xxx/s0</pre>
 * <br>Where xxx is the identifier of the wrapped key.
 *
 * This structure is used for representing both node keys and group
 * (private) keys. We encapsulate functionality to walk such a directory
 * and find our target key here.
 * 
 * Our model is that higher-level function may use this interface
 * to try many ways to get a given key. Some will work (access is
 * allowed), some may not -- the latter does not mean that the
 * principal doesn't have access, just that the principal doesn't
 * have access by this route. So for the moment, we return null
 * when we don't conclusively know that this principal doesn't
 * have access to this data somehow, rather than throwing
 * AccessDeniedException.
 */
public class KeyDirectory extends EnumeratedNameList {
	
	static Comparator<byte[]> byteArrayComparator = new ByteArrayCompare();
		
	GroupAccessControlManager _manager; // to get at key cache
	
	/**
	 * Maps the friendly names of principals (typically groups) to their information.
	 */
	HashMap<String, PrincipalInfo> _principals = new HashMap<String, PrincipalInfo>();
	private final ReadWriteLock _principalsLock = new ReentrantReadWriteLock();

	/**
	 * The set _keyIDs contains the digests of the (public) wrapping keys
	 * of the wrapped key objects stored in the KeyDirectory.
	 */
	TreeSet<byte []> _keyIDs = new TreeSet<byte []>(byteArrayComparator);
	private final ReadWriteLock _keyIDLock = new ReentrantReadWriteLock();

	/**
	 * The set _otherNames records the presence of superseded keys, previous keys, 
	 * group private keys, etc. 
	 */
	TreeSet<byte []> _otherNames = new TreeSet<byte []>(byteArrayComparator);
	private final ReadWriteLock _otherNamesLock = new ReentrantReadWriteLock();

	/**
	 * Directory name should be versioned, else we pull the latest version.
	 * @param manager the access control manager.
	 * @param directoryName the root of the KeyDirectory.
	 * @param handle
	 * @throws IOException
	 */
	public KeyDirectory(GroupAccessControlManager manager, ContentName directoryName, CCNHandle handle) 
					throws IOException {
		super(directoryName, handle);
		if (null == manager) {
			stopEnumerating();
			throw new IllegalArgumentException("Manager cannot be null.");
		}	
		_manager = manager;
		initialize();
	}

	private void initialize() throws IOException {
		if (!VersioningProfile.hasTerminalVersion(_namePrefix)) {
			getNewData();
			ContentName latestVersionName = getLatestVersionChildName();
			if (null == latestVersionName) {
				Log.info("Unexpected: can't get a latest version for key directory name : " + _namePrefix);
				getNewData();
				latestVersionName = getLatestVersionChildName();
				if (null == latestVersionName) {
					Log.info("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
					throw new IOException("Unexpected: really can't get a latest version for key directory name : " + _namePrefix);
				}
			}
			Log.finer("KeyDirectory, got latest version of {0}, name {1}", _namePrefix, latestVersionName);
			synchronized (_childLock) {
				stopEnumerating();
				_children.clear();
				_newChildren = null;
				if (latestVersionName.count() > 1) {
					Log.warning("Unexpected: NE protocol gave back more than one component!");
				}
				_namePrefix = new ContentName(_namePrefix, latestVersionName.component(0));
				_enumerator.registerPrefix(_namePrefix);
			}
		} 
	}
	
	/**
	 * Called each time new data comes in, gets to parse it and load processed
	 * arrays.
	 */
	@Override
	protected void processNewChildren(SortedSet<ContentName> newChildren) {
		for (ContentName childName : newChildren) {
			// currently encapsulated in single-component ContentNames
			byte [] wkChildName = childName.lastComponent();
			if (KeyProfile.isKeyNameComponent(wkChildName)) {
				byte[] keyid;
				try {
					keyid = KeyProfile.getKeyIDFromNameComponent(wkChildName);
					try{
						_keyIDLock.writeLock().lock();
						_keyIDs.add(keyid);
					}finally{
						_keyIDLock.writeLock().unlock();
					}
				} catch (IOException e) {
					Log.info("Unexpected " + e.getClass().getName() + " parsing key id " + DataUtils.printHexBytes(wkChildName) + ": " + e.getMessage());
					// ignore and go on
				}
			} else if (GroupAccessControlProfile.isPrincipalNameComponent(wkChildName)) {
				addPrincipal(wkChildName);
			} else {
				try{
					_otherNamesLock.writeLock().lock();
					_otherNames.add(wkChildName);
				}finally{
					_otherNamesLock.writeLock().unlock();
				}
			}
		}
	}
	
	/**
	 * Return a copy to avoid synchronization problems.
	 * @throws ContentNotReadyException 
	 * 
	 */
	public TreeSet<byte []> getCopyOfWrappingKeyIDs() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		TreeSet<byte []> copy = new TreeSet<byte []>(byteArrayComparator);
		try {
			_keyIDLock.readLock().lock();
			for (byte[] elt: _keyIDs) copy.add(elt);
		} finally {
			_keyIDLock.readLock().unlock();
		}
		return copy; 	
	}
	
	/**
	 * Return a copy to avoid synchronization problems.
	 * @throws ContentNotReadyException 
	 */
	public HashMap<String, PrincipalInfo> getCopyOfPrincipals() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		HashMap<String, PrincipalInfo> copy = new HashMap<String, PrincipalInfo>();
		try {
			_principalsLock.readLock().lock();
			for (String key: _principals.keySet()) {
				PrincipalInfo value = _principals.get(key);
				copy.put(key, value);
			}
		} finally {
			_principalsLock.readLock().unlock();
		}
		return copy; 	
	}
	
	/**
	 * Returns principal info
	 * @throws ContentNotReadyException 
	 */
	public PrincipalInfo getPrincipalInfo(String principal) throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		PrincipalInfo pi = null;
		try {
			_principalsLock.readLock().lock();
			pi = _principals.get(principal);
		} finally {
			_principalsLock.readLock().unlock();
		}
		return pi;
	}
	
	/**
	 * Returns a copy to avoid synchronization problems
	 * @throws ContentNotReadyException 
	 */
	public TreeSet<byte []> getCopyOfOtherNames() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		TreeSet<byte []> copy = new TreeSet<byte []>(byteArrayComparator);
		try {
			_otherNamesLock.readLock().lock();
			for (byte[] elt: _otherNames) copy.add(elt);
		} finally {
			_otherNamesLock.readLock().unlock();
		}
		return copy;	
	}
	
	/**
	 * Adds a principal name
	 * @param wkChildName the principal name
	 */
	protected void addPrincipal(byte [] wkChildName) {
		PrincipalInfo pi = GroupAccessControlProfile.parsePrincipalInfoFromNameComponent(wkChildName);
		try{
				_principalsLock.writeLock().lock();
				_principals.put(pi.friendlyName(), pi);
		}finally{
			_principalsLock.writeLock().unlock();
		}
	}
	
	/**
	 * Returns the wrapped key object corresponding to a public key specified by its digest.
	 * @param keyID the digest of the specified public key. 
	 * @return the corresponding wrapped key object.
	 * @throws ContentDecodingException 
	 * @throws IOException 
	 */
	public WrappedKeyObject getWrappedKeyForKeyID(byte [] keyID) throws ContentDecodingException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		try{
			_keyIDLock.readLock().lock();
			if (!_keyIDs.contains(keyID)) {
				return null;
			}
		}finally{
			_keyIDLock.readLock().unlock();
		}
		ContentName wrappedKeyName = getWrappedKeyNameForKeyID(keyID);
		return getWrappedKey(wrappedKeyName);
	}
	
	/**
	 * Returns the wrapped key name for a public key specified by its digest.
	 * @param keyID the digest of the public key.
	 * @return the corresponding wrapped key name.
	 */
	public ContentName getWrappedKeyNameForKeyID(byte [] keyID) {
		return KeyProfile.keyName(_namePrefix, keyID);
	}
	
	/**
	 * Returns the wrapped key object corresponding to a specified principal.
	 * @param principalName the principal.
	 * @return the corresponding wrapped key object.
	 * @throws IOException 
	 * @throws ContentNotReadyException
	 * @throws ContentDecodingException 
	 */
	public WrappedKeyObject getWrappedKeyForPrincipal(String principalName) 
		throws ContentNotReadyException, ContentDecodingException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		
		PrincipalInfo pi = null;
		try{
			_principalsLock.readLock().lock();
			if (!_principals.containsKey(principalName)) {
				return null;
			}
			pi = _principals.get(principalName);
		}finally{
			_principalsLock.readLock().unlock();
		}
		if (null == pi) {
			Log.info("No block available for principal: " + principalName);
			return null;
		}
		ContentName principalLinkName = getWrappedKeyNameForPrincipal(pi);
		// This should be a link to the actual key block
		// TODO DKS should wait on link data...
		LinkObject principalLink = new LinkObject(principalLinkName, _manager.handle());
		Log.info("Retrieving wrapped key for principal " + principalName + " at " + principalLink.getTargetName());
		ContentName wrappedKeyName = principalLink.getTargetName();
		return getWrappedKey(wrappedKeyName);
	}
	
	/**
	 * Returns the wrapped key name for a specified principal.
	 * @param isGroup whether the principal is a group.
	 * @param principalName the name of the principal.
	 * @param principalVersion the version of the principal.
	 * @return the corresponding wrapped key name. 
	 */
	public ContentName getWrappedKeyNameForPrincipal(PrincipalInfo pi) {
		ContentName principalLinkName = new ContentName(_namePrefix, 
				GroupAccessControlProfile.principalInfoToNameComponent(pi));
		return principalLinkName;
	}
	
	/**
	 * Returns the wrapped key name for a principal specified by the name of its public key. 
	 * @param principalPublicKeyName the name of the public key of the principal.
	 * @return the corresponding wrapped key name.
	 * @throws VersionMissingException
	 */
	public ContentName getWrappedKeyNameForPrincipal(ContentName principalPublicKeyName) throws VersionMissingException {
		PrincipalInfo info = GroupAccessControlProfile.parsePrincipalInfoFromPublicKeyName(_manager.groupManager().isGroup(principalPublicKeyName),
																					  principalPublicKeyName);
		return getWrappedKeyNameForPrincipal(info);
	}

	/**
	 * Checks for the existence of a superseded block.
	 * @throws ContentNotReadyException 
	 */
	public boolean hasSupersededBlock() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		boolean b = false;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(GroupAccessControlProfile.SUPERSEDED_MARKER.getBytes());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
	}
	
	public ContentName getSupersededBlockName() {
		return ContentName.fromNative(_namePrefix, GroupAccessControlProfile.SUPERSEDED_MARKER);
	}
	
	/**
	 * We have several choices for how to represent superseded and previous keys.
	 * Ignoring for now the case where we might have to have more than one per key directory
	 * (e.g. if we represent removal of several interposed ACLs), we could have the
	 * wrapped key block stored in the superseded block location, and the previous
	 * key block be a link, or the previous key block be a wrapped key and the superseded
	 * location be a link. Or we could store wrapped key blocks in both places. Because
	 * the wrapped key blocks can contain the name of the key that wrapped them (but
	 * not the key being wrapped), they are in essence a pointer forward to the replacing
	 * key. So, the superseded block, if it contains a wrapped key, is both a key and a link.
	 * If the block was stored at the previous key, it would not be both a key and a link,
	 * as its wrapping key is indicated by where it is. So it should indeed be a link -- 
	 * except in the case of an interposed ACL, where there is nothing to link to; 
	 * and it instead stores a wrapped key block containing the effective node key that
	 * was the previous key.
	 * This method checks for the existence of a superseded block.
	 * @return
	 * @throws ContentNotReadyException 
	 * @throws ContentDecodingException 
	 * @throws IOException
	 */
	public WrappedKeyObject getSupersededWrappedKey() throws ContentDecodingException, ContentNotReadyException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		if (!hasSupersededBlock())
			return null;
		return getWrappedKey(getSupersededBlockName());
	}
	
	/**
	 * Returns the wrapped key object corresponding to the specified wrapped key name.
	 * @param wrappedKeyName
	 * @return
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 */
	public WrappedKeyObject getWrappedKey(ContentName wrappedKeyName) throws ContentDecodingException, IOException {
		WrappedKeyObject wrappedKey = new WrappedKeyObject(wrappedKeyName, _manager.handle());
		wrappedKey.update();
		return wrappedKey;		
	}
	
	/**
	 * Checks for the existence of a previous key block
	 * @throws ContentNotReadyException 
	 */
	public boolean hasPreviousKeyBlock() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		boolean b;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(GroupAccessControlProfile.PREVIOUS_KEY_NAME.getBytes());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
	}

	public ContentName getPreviousKeyBlockName() {
		return getPreviousKeyBlockName(_namePrefix);
	}
	
	public static ContentName getPreviousKeyBlockName(ContentName keyDirectoryName) {
		return ContentName.fromNative(keyDirectoryName, GroupAccessControlProfile.PREVIOUS_KEY_NAME);		
	}
	
	/**
	 * Returns a link to the previous key.
	 * Previous key might be a link, if we're a simple newer version, or it might
	 * be a wrapped key, if we're an interposed node key. 
	 * DKS TODO
	 * @return
	 * @throws IOException 
	 * @throws ContentNotReadyException 
	 * @throws ContentDecodingException
	 */
	public Link getPreviousKey(long timeout) throws ContentNotReadyException, IOException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		if (!hasPreviousKeyBlock())
			return null;
		LinkObject previousKey = new LinkObject(getPreviousKeyBlockName(), _manager.handle());
		previousKey.waitForData(timeout); 
		if (!previousKey.available()) {
			Log.info("Unexpected: no previous key link at " + getPreviousKeyBlockName());
			return null;
		}
		return previousKey.link();
	}

	/**
	 * We store a private key as a single block wrapped under a nonce key, which is
	 * then wrapped under the public keys of various principals. The WrappedKey structure
	 * would allow us to do this (wrap private in public) in a single object, with
	 * an inline nonce key, but this option is more efficient.
	 * Checks for the existence of a private key block
	 * @return
	 * @throws ContentNotReadyException 
	 */
	public boolean hasPrivateKeyBlock() throws ContentNotReadyException {
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		boolean b;
		try{
			_otherNamesLock.readLock().lock();
			b = _otherNames.contains(GroupAccessControlProfile.GROUP_PRIVATE_KEY_NAME.getBytes());
		}finally{
			_otherNamesLock.readLock().unlock();
		}
		return b;
	}

	public ContentName getPrivateKeyBlockName() {
		return ContentName.fromNative(_namePrefix, GroupAccessControlProfile.GROUP_PRIVATE_KEY_NAME);
	}
	
	/**
	 * Returns the private key object as a wrapped key object.
	 * @return
	 * @throws IOException 
	 * @throws ContentGoneException 
	 * @throws ContentDecodingException 
	 */
	public WrappedKeyObject getPrivateKeyObject() throws ContentGoneException, IOException {
		if (!hasPrivateKeyBlock()) // checks hasChildren
			return null;
		
		return new WrappedKey.WrappedKeyObject(getPrivateKeyBlockName(), _manager.handle());
	}
	
	/**
	 * Unwrap and return the key wrapped in a wrapping key specified by its digest.
	 * Find a copy of the key block in this directory that we can unwrap (either the private
	 * key wrapping key block or a wrapped raw symmetric key). Chase superseding keys if
	 * we have to. This mechanism should be generic, and should work for node keys
	 * as well as private key wrapping keys in directories following this structure.
	 * @return
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws ContentDecodingException 
	 * @throws IOException 
	 */
	public Key getUnwrappedKey(byte [] expectedKeyID) 
			throws InvalidKeyException, InvalidCipherTextException, ContentDecodingException, IOException {
		
		WrappedKeyObject wko = null;
		Key unwrappedKey = null;
		byte [] retrievedKeyID = null;
		// Do we have one of the wrapping keys already in our cache?
		// (This list will be empty only if this key is GONE. If it is, we'll move on
		// to a superseding key below if there is one.)
		// Do we have one of the wrapping keys in our cache?
		
		if (!hasChildren()) {
			throw new ContentNotReadyException("Need to call waitForData(); assuming directory known to be non-empty!");
		}
		try {
			_keyIDLock.readLock().lock();
			for (byte [] keyid : _keyIDs) {
				if (_manager.hasKey(keyid)) {
					// We have it, pull the block, unwrap the node key.
					wko = getWrappedKeyForKeyID(keyid);
					if (null != wko.wrappedKey()) {
						unwrappedKey = wko.wrappedKey().unwrapKey(_manager.getKey(keyid));
					}
				}
			}
		} finally {
			_keyIDLock.readLock().unlock();
		}
		
		if (null == unwrappedKey) {
			// Not in cache. Is it superseded?
			if (hasSupersededBlock()) {
				// OK, is the superseding key just a newer version of this key? If it is, roll
				// forward to the latest version and work back from there.
				WrappedKeyObject supersededKeyBlock = getSupersededWrappedKey();
				if (null != supersededKeyBlock) {
					// We could just walk up superseding key hierarchy, and then walk back with
					// decrypted keys. Or we could attempt to jump all the way to the end and then
					// walk back. Not sure there is a significant win in doing the latter, so start
					// with the former... have to touch intervening versions in both cases.
					Log.info("Attempting to retrieve key " + getName() + " by retrieving superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					
					Key unwrappedSupersedingKey = null;
					KeyDirectory supersedingKeyDirectory = null;
					try {
						supersedingKeyDirectory = new KeyDirectory(_manager, supersededKeyBlock.wrappedKey().wrappingKeyName(), _manager.handle());
						supersedingKeyDirectory.waitForUpdates(SystemConfiguration.SHORT_TIMEOUT);
						// This wraps the key we actually want.
						unwrappedSupersedingKey = supersedingKeyDirectory.getUnwrappedKey(supersededKeyBlock.wrappedKey().wrappingKeyIdentifier());
					} finally {
						supersedingKeyDirectory.stopEnumerating();
					}
					if (null != unwrappedSupersedingKey) {
						_manager.addKey(supersedingKeyDirectory.getName(), unwrappedSupersedingKey);
						unwrappedKey = supersededKeyBlock.wrappedKey().unwrapKey(unwrappedSupersedingKey);
					} else {
						Log.info("Unable to retrieve superseding key " + supersededKeyBlock.wrappedKey().wrappingKeyName());
					}
				}

			} else {
				// This is the current key. Enumerate principals and see if we can get a key to unwrap.
				Log.info("At latest version of key " + getName() + ", attempting to unwrap.");
				// Assumption: if this key was encrypted directly for me, I would have had a cache
				// hit already. The assumption is that I pre-load my cache with my own private key(s).
				// So I don't care about principal entries if I get here, I only care about groups.
				// Groups may come in three types: ones I know I am a member of, but don't have this
				// particular key version for, ones I don't know anything about, and ones I believe
				// I'm not a member of but someone might have added me.
				if (_manager.groupManager().haveKnownGroupMemberships()) {
					try{
						_principalsLock.readLock().lock();
						for (String principal : _principals.keySet()) {
							if ((!_manager.groupManager().isGroup(principal)) || (!_manager.groupManager().amKnownGroupMember(principal))) {
								// On this pass, only do groups that I think I'm a member of. Do them
								// first as it is likely faster.
								continue;
							}
							// I know I am a member of this group, or at least I was last time I checked.
							// Attempt to get this version of the group private key as I don't have it in my cache.
							try {
								Key principalKey = _manager.groupManager().getVersionedPrivateKeyForGroup(this, principal);
								unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
								if (null == unwrappedKey)
									continue;
							} catch (AccessDeniedException aex) {
								// we're not a member
								continue;
							}
						}
					} finally {
						_principalsLock.readLock().unlock();
					}
				}
				if (null == unwrappedKey) {
					// OK, we don't have any groups we know we are a member of. Do the other ones.
					// Slower, as we crawl the groups tree.
					try{
							_principalsLock.readLock().lock();
							for (String principal : _principals.keySet()) {
								if ((!_manager.groupManager().isGroup(principal)) || (_manager.groupManager().amKnownGroupMember(principal))) {
									// On this pass, only do groups that I don't think I'm a member of.
									continue;
								}
								if (_manager.groupManager().amCurrentGroupMember(principal)) {
									try {
										Key principalKey = _manager.groupManager().getVersionedPrivateKeyForGroup(this, principal);
										unwrappedKey = unwrapKeyForPrincipal(principal, principalKey);
										if (null == unwrappedKey) {
											Log.warning("Unexpected: we are a member of group " + principal + " but get a null key.");
											continue;
										}
									} catch (AccessDeniedException aex) {
										Log.warning("Unexpected: we are a member of group " + principal + " but get an access denied exception when we try to get its key: " + aex.getMessage());
										continue;
									}
								}
							}
					} finally {
						_principalsLock.readLock().unlock();
					}
				}
			}
		}
		
		if (null != unwrappedKey) {
			_manager.addKey(getName(), unwrappedKey);

			if (null != expectedKeyID) {
				retrievedKeyID = NodeKey.generateKeyID(unwrappedKey);
				if (!Arrays.areEqual(expectedKeyID, retrievedKeyID)) {
					Log.warning("Retrieved and decrypted wrapped key, but it was the wrong key. We wanted " + 
							DataUtils.printBytes(expectedKeyID) + ", we got " + DataUtils.printBytes(retrievedKeyID));
				}
			}
		}
		// DKS TODO -- throw AccessDeniedException?
		return unwrappedKey;
	}
		
	/**
	 * Unwrap the key wrapped under a specified principal, with a specified unwrapping key.
	 * @param principal
	 * @param unwrappingKey
	 * @return
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws ContentDecodingException
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws IOException
	 */
	protected Key unwrapKeyForPrincipal(String principal, Key unwrappingKey) 
			throws InvalidKeyException, InvalidCipherTextException, ContentNotReadyException, 
					ContentDecodingException, ContentGoneException, IOException {		
		Key unwrappedKey = null;
		if (null == unwrappingKey) {
			Log.info("Null unwrapping key. Cannot unwrap.");
			return null;
		}
		WrappedKeyObject wko = getWrappedKeyForPrincipal(principal); // checks hasChildren
		if (null != wko.wrappedKey()) {
			unwrappedKey = wko.wrappedKey().unwrapKey(unwrappingKey);
		} else {
			try{
				_principalsLock.readLock().lock();
				Log.info("Unexpected: retrieved version " + _principals.get(principal) + " of " + principal + " group key, but cannot retrieve wrapped key object.");
			}finally{
				_principalsLock.readLock().unlock();
			}
		}
		return unwrappedKey;
	}
		
	/**
	 * Returns the private key stored in the KeyDirectory. 
	 * The private key is wrapped in a wrapping key, which is itself wrapped.
	 * So the unwrapping proceeds in two steps.
	 * First, we unwrap the wrapping key for the private key.
	 * Then, we unwrap the private key itself. 
	 * Relies on the caller, who presumably knows the public key, to add the result to the
	 * cache.
	 * @return
	 * @throws AccessDeniedException 
	 * @throws ContentGoneException 
	 * @throws ContentNotReadyException 
	 * @throws InvalidCipherTextException 
	 * @throws InvalidKeyException 
	 * @throws ContentDecodingException
	 * @throws IOException
	 */
	public PrivateKey getPrivateKey() 
			throws AccessDeniedException, InvalidKeyException, InvalidCipherTextException, 
					ContentNotReadyException, ContentGoneException, ContentDecodingException, IOException {
		if (!hasPrivateKeyBlock()) { // checks hasChildren
			Log.info("No private key block exists with name " + getPrivateKeyBlockName());
			return null;
		}
		WrappedKeyObject wko = getPrivateKeyObject();
		if ((null == wko) || (null == wko.wrappedKey())) {
			Log.info("Cannot retrieve wrapped private key for " + getPrivateKeyBlockName());
			return null;
		}
		// This should throw AccessDeniedException...
		Key wrappingKey = getUnwrappedKey(wko.wrappedKey().wrappingKeyIdentifier());
		if (null == wrappingKey) {
			Log.info("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
			throw new AccessDeniedException("Cannot get key to unwrap private key " + getPrivateKeyBlockName());
		}
		
		Key unwrappedPrivateKey = wko.wrappedKey().unwrapKey(wrappingKey);
		if (!(unwrappedPrivateKey instanceof PrivateKey)) {
			Log.info("Unwrapped private key is not an instance of PrivateKey! Its an " + unwrappedPrivateKey.getClass().getName());
		} else {
			Log.info("Unwrapped private key is a private key, in fact it's a " + unwrappedPrivateKey.getClass().getName());
		}
		return (PrivateKey)unwrappedPrivateKey;
	}

	/**
	 * Writes a wrapped key block to the repository.
	 * Eventually aggregate signing and repo stream operations at the very
	 * least across writing paired objects and links, preferably across larger
	 * swaths of data.
	 * @param secretKeyToWrap either a node key, a data key, or a private key wrapping key
	 * @param publicKeyName the name of the public key.
	 * @param publicKey the public key.
	 * @throws ContentEncodingException 
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 * @throws VersionMissingException 
	 * @throws VersionMissingException
	 */
	public void addWrappedKeyBlock(Key secretKeyToWrap, 
								   ContentName publicKeyName, PublicKey publicKey) 
			throws ContentEncodingException, IOException, InvalidKeyException, VersionMissingException {
		WrappedKey wrappedKey = WrappedKey.wrapKey(secretKeyToWrap, null, null, publicKey);
		wrappedKey.setWrappingKeyIdentifier(publicKey);
		wrappedKey.setWrappingKeyName(publicKeyName);
		WrappedKeyObject wko = 
			new WrappedKeyObject(getWrappedKeyNameForKeyID(WrappedKey.wrappingKeyIdentifier(publicKey)),
								 wrappedKey,SaveType.REPOSITORY, _manager.handle());
		wko.save();
		LinkObject lo = new LinkObject(getWrappedKeyNameForPrincipal(publicKeyName), new Link(wko.getVersionedName()), SaveType.REPOSITORY, _manager.handle());
		lo.save();
	}
	
	/**
	 * Writes a private key block to the repository. 
	 * @param privateKey the private key.
	 * @param privateKeyWrappingKey the wrapping key used to wrap the private key.
	 * @throws ContentEncodingException 
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 */
	public void addPrivateKeyBlock(PrivateKey privateKey, Key privateKeyWrappingKey)
			throws ContentEncodingException, IOException, InvalidKeyException {
		
		WrappedKey wrappedKey = WrappedKey.wrapKey(privateKey, null, null, privateKeyWrappingKey);	
		wrappedKey.setWrappingKeyIdentifier(privateKeyWrappingKey);
		WrappedKeyObject wko = new WrappedKeyObject(getPrivateKeyBlockName(), wrappedKey, SaveType.REPOSITORY, _manager.handle());
		wko.save();
	}

	/**
	 * Add a superseded-by block to our key directory.
	 * @param oldPrivateKeyWrappingKey
	 * @param supersedingKeyName
	 * @param newPrivateKeyWrappingKey
	 * @throws ContentEncodingException 
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 */
	public void addSupersededByBlock(Key oldPrivateKeyWrappingKey,
			ContentName supersedingKeyName, Key newPrivateKeyWrappingKey) 
			throws InvalidKeyException, ContentEncodingException, IOException {
		
		addSupersededByBlock(getSupersededBlockName(), oldPrivateKeyWrappingKey,
						     supersedingKeyName, newPrivateKeyWrappingKey, _manager.handle());
	}
	
	/**
	 * Add a superseded-by block to another node key, where we may have only its name, not its enumeration.
	 * Use as a static method to add our own superseded-by blocks as well.
	 * @throws ContentEncodingException 
	 * @throws IOException 
	 * @throws InvalidKeyException 
	 */
	public static void addSupersededByBlock(ContentName oldKeySupersededBlockName, Key oldKeyToBeSuperseded, 
											ContentName supersedingKeyName, Key supersedingKey, CCNHandle handle) 
			throws ContentEncodingException, IOException, InvalidKeyException {
		
		WrappedKey wrappedKey = WrappedKey.wrapKey(oldKeyToBeSuperseded, null, null, supersedingKey);
		wrappedKey.setWrappingKeyIdentifier(supersedingKey);
		wrappedKey.setWrappingKeyName(supersedingKeyName);
		WrappedKeyObject wko = new WrappedKeyObject(oldKeySupersededBlockName, wrappedKey, SaveType.REPOSITORY, handle);
		wko.save();
	}

	/**
	 * Writes a link to a previous key to the repository. 
	 * @param previousKey
	 * @param previousKeyPublisher
	 * @throws ContentEncodingException 
	 * @throws IOException 
	 */ 
	public void addPreviousKeyLink(ContentName previousKey, PublisherID previousKeyPublisher) 
				throws ContentEncodingException, IOException {
		
		if (hasPreviousKeyBlock()) {
			Log.warning("Unexpected, already have previous key block : " + getPreviousKeyBlockName());
		}
		LinkAuthenticator la = (null != previousKeyPublisher) ? new LinkAuthenticator(previousKeyPublisher) : null;
		LinkObject pklo = new LinkObject(getPreviousKeyBlockName(), new Link(previousKey,la), SaveType.REPOSITORY, _manager.handle());
		pklo.save();
	}
	
	/**
	 * Writes a previous key block to the repository. 
	 * @param oldPrivateKeyWrappingKey
	 * @param supersedingKeyName
	 * @param newPrivateKeyWrappingKey
	 * @throws InvalidKeyException 
	 * @throws ContentEncodingException 
	 * @throws IOException 
	 */
	public void addPreviousKeyBlock(Key oldPrivateKeyWrappingKey,
									ContentName supersedingKeyName, Key newPrivateKeyWrappingKey) 
				throws InvalidKeyException, ContentEncodingException, IOException {
		// DKS TODO -- do we need in the case of deletion of ACLs to allow for multiple previous key blocks simultaneously?
		// Then need to add previous key id to previous key block name.
		WrappedKey wrappedKey = WrappedKey.wrapKey(oldPrivateKeyWrappingKey, null, null, newPrivateKeyWrappingKey);
		wrappedKey.setWrappingKeyIdentifier(newPrivateKeyWrappingKey);
		wrappedKey.setWrappingKeyName(supersedingKeyName);
		WrappedKeyObject wko = new WrappedKeyObject(getPreviousKeyBlockName(), wrappedKey,SaveType.REPOSITORY,  _manager.handle());
		wko.save();
	}
}
