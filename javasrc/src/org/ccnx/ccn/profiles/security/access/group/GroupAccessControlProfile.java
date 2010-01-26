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


import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;

/**
 * This class specifies how a number of access control elements are named:
 * - users, and their keys
 * - groups, and their keys
 * - access control lists (ACLs)
 * - node keys, and their encryption under ACL member keys
 * - if used, markers indicating where to find ACLs/node keys
 */
public class GroupAccessControlProfile extends AccessControlProfile implements CCNProfile {
	
	// These may eventually want to move somewhere more general
	public static final String GROUP_PREFIX = "Groups";
	public static final byte [] GROUP_PREFIX_BYTES = ContentName.componentParseNative(GROUP_PREFIX);
	public static final String USER_PREFIX = "Users";
	public static final byte [] USER_PREFIX_BYTES = ContentName.componentParseNative(USER_PREFIX);
	
	public static final String GROUP_PUBLIC_KEY_NAME = "Key";
	public static final String GROUP_PRIVATE_KEY_NAME = "PrivateKey";
	public static final String GROUP_MEMBERSHIP_LIST_NAME = "MembershipList";
	public static final String GROUP_POINTER_TO_PARENT_GROUP_NAME = "PointerToParentGroup";
	public static final String PREVIOUS_KEY_NAME = "PreviousKey";
	public static final String ACL_NAME = "ACL";
	public static final byte [] ACL_NAME_BYTES = ContentName.componentParseNative(ACL_NAME);
	public static final String NODE_KEY_NAME = "NK";
	public static final byte [] NODE_KEY_NAME_BYTES = ContentName.componentParseNative(NODE_KEY_NAME);	
	// These two must be the same length
	public static final byte [] PRINCIPAL_PREFIX = ContentName.componentParseNative("p" + CCNProfile.COMPONENT_SEPARATOR_STRING);
	public static final byte [] GROUP_PRINCIPAL_PREFIX = ContentName.componentParseNative("g" + CCNProfile.COMPONENT_SEPARATOR_STRING);

	public static final String SUPERSEDED_MARKER = "SupersededBy";
	
	/**
	 * This class records information about a CCN principal.
	 * This information includes: 
	 * - principal type (group, etc),
	 * - friendly name (the name the principal is known by)
	 * - version
	 *
	 */
	public static class PrincipalInfo {
		private byte [] _typeMarker;
		private String _friendlyName;
		private CCNTime _versionTimestamp;
		
		public PrincipalInfo(byte [] type, String friendlyName, CCNTime versionTimestamp) {
			_typeMarker = type;
			_friendlyName = friendlyName;
			_versionTimestamp = versionTimestamp;
		}
		
		public boolean isGroup() { return Arrays.areEqual(GROUP_PRINCIPAL_PREFIX, _typeMarker); }
		public String friendlyName() { return _friendlyName; }
		public CCNTime versionTimestamp() { return _versionTimestamp; }
	}
	
	/**
	 * Returns whether the specified name is the name of a node key
	 * @param name the name
	 * @return
	 */
	public static boolean isNodeKeyName(ContentName name) {
		if (!isAccessName(name) || !VersioningProfile.hasTerminalVersion(name)) {
			return false;
		}
		int versionComponent = VersioningProfile.findLastVersionComponent(name);
		if (name.stringComponent(versionComponent - 1).equals(NODE_KEY_NAME)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Returns whether the specified name contains the group prefix
	 * @param name the name
	 * @return
	 */
	public static boolean isGroupName(ContentName name) {
		return name.contains(GROUP_PREFIX_BYTES);
	}
	
	/**
	 * Get the name of the node key for a given content node, if there is one.
	 * This is nodeName/_access_/NK, with a version then added for a specific node key.
	 * @param nodeName the name of the content node
	 * @return the name of the corresponding node key
	 */
	public static ContentName nodeKeyName(ContentName nodeName) {
		ContentName rootName = accessRoot(nodeName);
		ContentName nodeKeyName = new ContentName(rootName, ACCESS_CONTROL_MARKER_BYTES, NODE_KEY_NAME_BYTES);
		return nodeKeyName;
	}
	
	/**
	 * Get the name of the access control list (ACL) for a given content node.
	 * This is nodeName/_access_/ACL.
	 * @param nodeName the name of the content node
	 * @return the name of the corresponding ACL
	 */
	public static ContentName aclName(ContentName nodeName) {
		ContentName baseName = accessRoot(nodeName);
		ContentName aclName = new ContentName(baseName, ACCESS_CONTROL_MARKER_BYTES, ACL_NAME_BYTES);
		return aclName;
	}
	
	/**
	 * Get the name of the user namespace.
	 * This assumes a top-level namespace, where the group information is stored in 
	 * namespace/Groups and namespace/Users..
	 * @param namespace the top-level name space
	 * @return the name of the user namespace
	 */
	public static ContentName userNamespaceName(ContentName namespace) {
		return new ContentName(accessRoot(namespace), USER_PREFIX_BYTES);
	}

	/**
	 * Get the name of the namespace for a specified user.
	 * @param userNamespace the name of the user namespace
	 * @param userName the user name
	 * @return the name of the namespace for the user
	 */
	public static ContentName userNamespaceName(ContentName userNamespace,
			String userName) {
		return ContentName.fromNative(userNamespace, userName);
	}
	
	/**
	 * Get the name of the group namespace.
	 * This assumes a top-level namespace, where the group information is stored in 
	 * namespace/Groups and namespace/Users..
	 * @param namespace the top-level name space
	 * @return the name of the group namespace
	 */
	public static ContentName groupNamespaceName(ContentName namespace) {
		return new ContentName(accessRoot(namespace), GROUP_PREFIX_BYTES);
	}
	
	/**
	 * Get the name of the namespace for a specified group.
	 * @param namespace the top-level namespace
	 * @param groupFriendlyName the name of the group
	 * @return the name of the namespace for the group
	 */
	public static ContentName groupName(ContentName namespace, String groupFriendlyName) {
		return ContentName.fromNative(groupNamespaceName(namespace), groupFriendlyName);
	}
	
	/**
	 * Get the name of a group public key.
	 * This is the unversioned root. The actual public key is stored at the latest version of
	 * this name. The private key and decoding blocks are stored under that version, with
	 * the segments of the group public key.
	 * @param groupNamespaceName the namespace of the group
	 * @param groupFriendlyName the name of the group
	 * @return the name of the group public key
	 */
	public static ContentName groupPublicKeyName(ContentName groupNamespaceName, String groupFriendlyName) {
		return ContentName.fromNative(ContentName.fromNative(groupNamespaceName, groupFriendlyName),  GROUP_PUBLIC_KEY_NAME);
	}
	
	/**
	 * Get the name of the public key of a group specified by its full name
	 * @param groupFullName the full name of the group
	 * @return the name of the group public key
	 */
	public static ContentName groupPublicKeyName(ContentName groupFullName) {
		return ContentName.fromNative(groupFullName,  GROUP_PUBLIC_KEY_NAME);
	}
	
	/**
	 * Get the name of a group membership list for a specified group
	 * @param groupNamespaceName the namespace of the group
	 * @param groupFriendlyName the name of the group
	 * @return the name of the group membership list
	 */
	public static ContentName groupMembershipListName(ContentName groupNamespaceName, String groupFriendlyName) {
		return ContentName.fromNative(ContentName.fromNative(groupNamespaceName, groupFriendlyName),  GROUP_MEMBERSHIP_LIST_NAME);
	}

	/**
	 * Get the friendly name of a specified group
	 * @param groupName the full name of the group
	 * @return the friendly name of the group
	 */
	public static String groupNameToFriendlyName(ContentName groupName) {
		return ContentName.componentPrintNative(groupName.lastComponent());
	}

	/**
	 * Get the name of a group private key.
	 * We hang the wrapped private key directly off the public key version.
	 * @param groupPublicKeyNameAndVersion the versioned name of the group public key
	 * @return the versioned name of the group private key
	 */
	public static ContentName groupPrivateKeyDirectory(ContentName groupPublicKeyNameAndVersion) {
		return groupPublicKeyNameAndVersion;
	}
	
	public static ContentName groupPointerToParentGroupName(ContentName groupFullName) {
		return ContentName.fromNative(groupFullName, GROUP_POINTER_TO_PARENT_GROUP_NAME);
	}

	/**
	 * Returns whether a specified name component is the name of a principal
	 * @param nameComponent the name component
	 * @return
	 */
	public static boolean isPrincipalNameComponent(byte [] nameComponent) {
		return (DataUtils.isBinaryPrefix(PRINCIPAL_PREFIX, nameComponent) ||
				DataUtils.isBinaryPrefix(GROUP_PRINCIPAL_PREFIX, nameComponent));
	}

	/**
	 * Get the principalInfo corresponding to a specified name component
	 * @param childName the name component
	 * @return the corresponding principal info
	 */
	public static PrincipalInfo parsePrincipalInfoFromNameComponent(
			byte[] childName) {
		if (!isPrincipalNameComponent(childName) || (childName.length <= PRINCIPAL_PREFIX.length))
			return null;
		
		// First time we see COMPONENT_SEPARATOR is the separation point.
		// Could jump back based on fixed width of timestamp.
		int sepIndex = -1;
		for (sepIndex = PRINCIPAL_PREFIX.length; sepIndex < childName.length; sepIndex++) {
			if (childName[sepIndex] == CCNProfile.COMPONENT_SEPARATOR[0])
				break;
		}
		if (sepIndex == childName.length) {
			Log.warning("Unexpected principal name format - no separator: " + 
					ContentName.componentPrintURI(childName, PRINCIPAL_PREFIX.length, childName.length-PRINCIPAL_PREFIX.length));
			return null;
		}
		byte [] type = new byte[PRINCIPAL_PREFIX.length];
		byte [] principal = new byte[sepIndex - PRINCIPAL_PREFIX.length];
		byte [] timestamp = new byte[childName.length - sepIndex -1];
		System.arraycopy(childName, 0, type, 0, PRINCIPAL_PREFIX.length);
		System.arraycopy(childName, PRINCIPAL_PREFIX.length, principal, 0, principal.length);
		System.arraycopy(childName, sepIndex+1, timestamp, 0, timestamp.length);
		
		String strPrincipal = ContentName.componentPrintNative(principal);
		// Represent as version or just the timestamp part?
		CCNTime version = new CCNTime(timestamp);
		return new PrincipalInfo(type, strPrincipal, version);	
	}

	/**
	 * Principal names for links to wrapped key blocks take the form:
	 * {GROUP_PRINCIPAL_PREFIX | PRINCIPAL_PREFIX} COMPONENT_SEPARATOR principalName COMPONENT_SEPARATOR timestamp as 12-bit binary
	 * This allows a single enumeration of a wrapped key directory to determine
	 * not only which principals the keys are wrapped for, but also what versions of their
	 * private keys the keys are wrapped under (also determinable from the contents of the
	 * wrapped key blocks, but to do that you have to pull the wrapped key block).
	 * These serve as the name of a link to the actual wrapped key block.
	 * @param isGroup
	 * @param principalName
	 * @param timestamp
	 * @return
	 */
	public static byte[] principalInfoToNameComponent(PrincipalInfo pi) {
		byte [] bytePrincipal = ContentName.componentParseNative(pi.friendlyName());
		byte [] byteTime = pi.versionTimestamp().toBinaryTime();
		byte [] prefix = (pi.isGroup() ? GROUP_PRINCIPAL_PREFIX : PRINCIPAL_PREFIX);
		byte [] component = new byte[prefix.length + bytePrincipal.length + CCNProfile.COMPONENT_SEPARATOR.length + byteTime.length];
		// java 1.6 has much better functions for array copying
		System.arraycopy(prefix, 0, component, 0, prefix.length);
		System.arraycopy(bytePrincipal, 0, component, prefix.length, bytePrincipal.length);
		System.arraycopy(CCNProfile.COMPONENT_SEPARATOR, 0, component, prefix.length+bytePrincipal.length, CCNProfile.COMPONENT_SEPARATOR.length);
		System.arraycopy(byteTime, 0, component, prefix.length+bytePrincipal.length+CCNProfile.COMPONENT_SEPARATOR.length, 
				byteTime.length);
		
		return component;
	}
	
	/**
	 * Parses the principal name from a group public key.
	 * For groups, the last component of the public key is GROUP_PUBLIC_KEY_NAME = "Key".
	 * The principal name is the one-before-last component.
	 * @param publicKeyName the name of the group public key
	 * @return the corresponding principal name
	 */
	public static String parsePrincipalNameFromGroupPublicKeyName(ContentName publicKeyName) {
		ContentName cn = VersioningProfile.cutTerminalVersion(publicKeyName).first();
		return ContentName.componentPrintNative(cn.component(cn.count() - 2));
	}
	
	/**
	 * Parses the principal name from a public key name.
	 * Do not use this method for group public keys.
	 * For groups, use instead parsePrincipalNameFromGroupPublicKeyName
	 * @param publicKeyName the public key name
	 * @return the corresponding principal name
	 */
	public static String parsePrincipalNameFromPublicKeyName(ContentName publicKeyName) {
		return ContentName.componentPrintNative(VersioningProfile.cutTerminalVersion(publicKeyName).first().lastComponent());
	}

	/**
	 * Parse the principal info for a specified public key name
	 * @param isGroup whether the principal is a group
	 * @param publicKeyName the public key name
	 * @return the corresponding principal info
	 * @throws VersionMissingException
	 */
	public static PrincipalInfo parsePrincipalInfoFromPublicKeyName(boolean isGroup, ContentName publicKeyName) throws VersionMissingException {
		byte [] type = (isGroup ? GROUP_PRINCIPAL_PREFIX : PRINCIPAL_PREFIX);
		CCNTime version = VersioningProfile.getLastVersionAsTimestamp(publicKeyName);
		
		String principal;
		if (isGroup) principal = parsePrincipalNameFromGroupPublicKeyName(publicKeyName);
		else principal = parsePrincipalNameFromPublicKeyName(publicKeyName);
		
		return new PrincipalInfo(type, principal, version);
	}
}
