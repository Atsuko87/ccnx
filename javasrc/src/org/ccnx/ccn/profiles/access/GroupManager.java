package org.ccnx.ccn.profiles.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.access.AccessControlProfile.PrincipalInfo;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.PublisherID;


public class GroupManager {
	
	private AccessControlManager _accessManager;
	private ContentName _groupStorage;
	private EnumeratedNameList _groupList;
	private HashMap<String, Group> _groupCache = new HashMap<String, Group>();
	private HashSet<String> _myGroupMemberships = new HashSet<String>();
	private CCNHandle _library;

	public GroupManager(AccessControlManager accessManager,
						ContentName groupStorage, CCNHandle library) throws IOException {
		_library = library;
		_accessManager = accessManager;
		_groupStorage = groupStorage;
		groupList();
	}
	
	public AccessControlManager getAccessManager() { return _accessManager; }
	
	public EnumeratedNameList groupList() throws IOException {
		if (null == _groupList) {
			System.out.println("enumerating group: ......");
			System.out.println(_groupStorage);
			_groupList = new EnumeratedNameList(_groupStorage, _library);
		}
		return _groupList;
	}
	
	public Group getGroup(String groupFriendlyName) throws IOException, ConfigurationException, XMLStreamException {
		if ((null == groupFriendlyName) || (groupFriendlyName.length() == 0)) {
			Log.info("Asked to retrieve group with empty name.");
			return null;
		}
		Group theGroup = _groupCache.get(groupFriendlyName);
		
		//elaine : need to wait for data and add time out... the first time you run this, 
		// nothing will be read... 
		/*if( null == theGroup) {
			groupList().waitForData();
			SortedSet<ContentName> children = groupList().getChildren();
			System.out.println("found the following groups:.................");
			for (ContentName child : children) {
				System.out.println(child);
			}
		}*/
		
		
		if ((null == theGroup) && (groupList().hasChild(groupFriendlyName))) {
			// Only go hunting for it if we think it exists, otherwise we'll block.
			synchronized(_groupCache) {
				theGroup = _groupCache.get(groupFriendlyName);
				if (null == theGroup) {
					theGroup = new Group(_groupStorage, groupFriendlyName, _library,this);
					// wait for group to be ready?
					_groupCache.put(groupFriendlyName, theGroup);
				}
			}
		}
		// either we've got it, or we don't believe it exists.
		// DKS startup transients? do we need to block for group list?
		return theGroup;
	}
	
	public Group getGroup(Link theGroup) throws IOException, ConfigurationException, XMLStreamException {
		if (null == theGroup) {
			Log.info("Asked to retrieve group with empty link.");
			return null;
		}
		if (!isGroup(theGroup))
			return null;
		String friendlyName = AccessControlProfile.groupNameToFriendlyName(theGroup.targetName());
		return getGroup(friendlyName);
	}
	
	public void cacheGroup(Group newGroup) {
		synchronized(_groupCache) {
			_groupCache.put(newGroup.friendlyName(), newGroup);
		}
	}
	
	public Group createGroup(String groupFriendlyName, ArrayList<Link> newMembers) 
				throws XMLStreamException, IOException, ConfigurationException, InvalidKeyException, 
						InvalidCipherTextException, AccessDeniedException {
		Group existingGroup = getGroup(groupFriendlyName);
		if (null != existingGroup) {
			existingGroup.setMembershipList(this, newMembers);
			return existingGroup;
		} else {
			// Need to make key pair, directory, and store membership list.
			MembershipList ml = 
				new MembershipList(
						AccessControlProfile.groupMembershipListName(_groupStorage, groupFriendlyName), 
						new Collection(newMembers), _library);
			Group newGroup =  new Group(_groupStorage, groupFriendlyName, ml, _library, this);
			cacheGroup(newGroup);
			// If I'm a group member (I end up knowing the private key of the group if I
			// created it, but I could simply forget it...).
			if (amCurrentGroupMember(newGroup)) {
				_myGroupMemberships.add(groupFriendlyName);
			}
			return newGroup;
		}
	}
	
		
	public void deleteGroup(String friendlyName) throws IOException, ConfigurationException, XMLStreamException {
		Group existingGroup = getGroup(friendlyName);
		
		// DKS we really want to be sure we get the group if it's out there...
		if (null != existingGroup) {
			Log.info("Got existing group to delete: " + existingGroup);
			existingGroup.delete();
		} else {
			Log.warning("No existing group: " + friendlyName + ", ignoring delete request.");
		}
	}
	
	/**
	 * Does this member refer to a user or a group. Groups have to be in the
	 * group namespace, users can be anywhere.
	 * @param member
	 * @return
	 */
	public boolean isGroup(Link member) {
		return _groupStorage.isPrefixOf(member.targetName());
	}
	
	public boolean isGroup(String principal) {
		return _groupList.hasChild(principal);
	}
	
	public boolean isGroup(ContentName publicKeyName) {
		return _groupStorage.isPrefixOf(publicKeyName);
	}

	public boolean haveKnownGroupMemberships() {
		return _myGroupMemberships.size() > 0;
	}

	public boolean amKnownGroupMember(String principal) {
		return _myGroupMemberships.contains(principal);
	}

	public boolean amCurrentGroupMember(String principal) throws IOException, XMLStreamException, ConfigurationException {
		return amCurrentGroupMember(getGroup(principal));
	}
	
	/**
	 * Start out doing this the slow and simple way. Optimize later.
	 * @param group
	 * @return
	 * @throws IOException 
	 * @throws XMLStreamException 
	 * @throws ConfigurationException 
	 */
	public boolean amCurrentGroupMember(Group group) throws IOException, XMLStreamException, ConfigurationException {
		MembershipList ml = group.membershipList(); // will update
		for (Link lr : ml.membershipList().contents()) {
			if (isGroup(lr)) {
				String groupFriendlyName = AccessControlProfile.groupNameToFriendlyName(lr.targetName());
				if (amCurrentGroupMember(groupFriendlyName)) {
					_myGroupMemberships.add(groupFriendlyName);
					return true;
				} else {
					// Don't need to test first. Won't remove if isn't there.
					_myGroupMemberships.remove(groupFriendlyName);
				}
			} else {
				// Not a group. Is it me?
				if (_accessManager.haveIdentity(lr.targetName())) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * I already believe I should have access to this private key.
	 * @param group
	 * @param privateKeyVersion
	 * @return
	 * @throws XMLStreamException 
	 * @throws IOException 
	 * @throws InvalidCipherTextException 
	 * @throws AccessDeniedException 
	 * @throws InvalidKeyException 
	 * @throws AccessDeniedException 
	 * @throws ConfigurationException 
	 */
	public PrivateKey getGroupPrivateKey(String groupFriendlyName, Timestamp privateKeyVersion) throws InvalidKeyException, InvalidCipherTextException, IOException, XMLStreamException, AccessDeniedException, ConfigurationException {
		// Heuristic check
		if (!amKnownGroupMember(groupFriendlyName)) {
			Log.info("Unexpected: we don't think we're a group member of group " + groupFriendlyName);
		}
		// Need to get the KeyDirectory for this version of the private key, or the 
		// latest if no version given.
		KeyDirectory privateKeyDirectory = null;
		PublicKey theGroupPublicKey = null;
		if (null == privateKeyVersion) {
			Group theGroup = getGroup(groupFriendlyName); // will pull latest public key
			privateKeyDirectory = theGroup.privateKeyDirectory(_accessManager);
			theGroupPublicKey = theGroup.publicKey();
		} else {
			// Assume one is there...
			ContentName versionedPublicKeyName = 
				VersioningProfile.addVersion(
						AccessControlProfile.groupPublicKeyName(_groupStorage, groupFriendlyName),
						privateKeyVersion);
			privateKeyDirectory =
				new KeyDirectory(_accessManager, 
					AccessControlProfile.groupPrivateKeyDirectory(versionedPublicKeyName), _library);
			PublicKeyObject thisPublicKey = new PublicKeyObject(versionedPublicKeyName, _library);
			theGroupPublicKey = thisPublicKey.publicKey();
		}
		if (null == privateKeyDirectory) {
			Log.info("Unexpected: null private key directory for group " + groupFriendlyName + " version " + privateKeyVersion + " as stamp " + 
					DataUtils.printHexBytes(DataUtils.timestampToBinaryTime12(privateKeyVersion)));
			return null;
		}
		PrivateKey privateKey = privateKeyDirectory.getPrivateKey();
		if (null != privateKey) {
			_accessManager.keyCache().addPrivateKey(privateKeyDirectory.getName(), PublisherID.generatePublicKeyDigest(theGroupPublicKey), 
					privateKey);
		}
		return privateKey;
	}

	/**
	 * We might or might not still be a member of this group, or be a member
	 * again. This merely removes our cached notion that we are a member.
	 * @param principal
	 */
	public void removeGroupMembership(String principal) {
		_myGroupMemberships.remove(principal);
	}
	
	/**
	 * Eventually let namespace control this.
	 * @return
	 */
	public String getGroupKeyAlgorithm() {
		return AccessControlManager.DEFAULT_GROUP_KEY_ALGORITHM;
	}

	protected Key getVersionedPrivateKeyForGroup(KeyDirectory keyDirectory, String principal) 
			throws IOException, InvalidKeyException, AccessDeniedException, InvalidCipherTextException, 
					XMLStreamException, ConfigurationException {
		PrincipalInfo pi = keyDirectory.getPrincipals().get(principal);
		if (null == pi) {
			Log.info("No key available for principal : " + principal + " on node " + keyDirectory.getName());
			return null;
		}
		Key privateKey = getGroupPrivateKey(principal, pi.versionTimestamp());
		if (null == privateKey) {
			Log.info("Unexpected: we beleive we are a member of group " + principal + " but cannot retrieve private key version: " + keyDirectory.getPrincipals().get(principal) + " our membership revoked?");
			// Check to see if we are a current member.
			if (!amCurrentGroupMember(principal)) {
				// Removes this group from my list of known groups, adds it to my
				// list of groups I don't believe I'm a member of.
				removeGroupMembership(principal);
			}
		}
		return privateKey;
	}

	public PublicKeyObject getLatestPublicKeyForGroup(Link principal) throws IOException, ConfigurationException, XMLStreamException {
		Group theGroup = getGroup(principal);
		if (null == theGroup) 
			return null;
		return theGroup.publicKeyObject();
	}

}