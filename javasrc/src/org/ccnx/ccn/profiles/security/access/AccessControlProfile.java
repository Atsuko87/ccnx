package org.ccnx.ccn.profiles.security.access;

import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;

public class AccessControlProfile {

	public static final String ACCESS_CONTROL_MARKER = CCNProfile.MARKER + "access" + CCNProfile.MARKER;
	public static final byte [] ACCESS_CONTROL_MARKER_BYTES = ContentName.componentParseNative(ACCESS_CONTROL_MARKER);
	public static final String ROOT_NAME = "ROOT";
	public static final byte [] ROOT_NAME_BYTES = ContentName.componentParseNative(ROOT_NAME);
	public static final String DATA_KEY_NAME = "DK";
	public static final byte [] DATA_KEY_NAME_BYTES = ContentName.componentParseNative(DATA_KEY_NAME);
	protected static final ContentName ROOT_POSTFIX_NAME = new ContentName(new byte [][] {ACCESS_CONTROL_MARKER_BYTES, ROOT_NAME_BYTES});

	/**
	 * Returns whether the specified name contains the access control marker
	 * @param name the name
	 * @return
	 */
	public static boolean isAccessName(ContentName name) {
		return name.contains(ACCESS_CONTROL_MARKER_BYTES);
	}

	/**
	 * Truncates the specified name at the access control marker
	 * @param name the name
	 * @return the truncated name
	 */
	public static ContentName accessRoot(ContentName name) {
		return name.cut(ACCESS_CONTROL_MARKER_BYTES);
	}

	/**
	 * Return the name of the root access control policy information object,
	 * if it is stored at node nodeName.
	 **/
	public static ContentName rootName(ContentName nodeName) {
		ContentName baseName = (isAccessName(nodeName) ? accessRoot(nodeName) : nodeName);
		ContentName aclRootName = baseName.append(rootPostfix());
		return aclRootName;
	}
	
	/**
	 * Return the set of name components to add to an access root to get the root name.
	 */
	public static ContentName rootPostfix() {
		return ROOT_POSTFIX_NAME;
	}

	/**
	 * Get the name of the data key for a given content node.
	 * This is nodeName/_access_/DK.
	 * @param nodeName the name of the content node
	 * @return the name of the corresponding data key
	 */
	public static ContentName dataKeyName(ContentName nodeName) {
		ContentName baseName = accessRoot(nodeName);
		ContentName dataKeyName = new ContentName(baseName, ACCESS_CONTROL_MARKER_BYTES, DATA_KEY_NAME_BYTES);
		return dataKeyName;
	}

	/**
	 * Returns whether the specified name is a data key name
	 * @param name the name
	 * @return
	 */
	public static boolean isDataKeyName(ContentName name) {
		if (!isAccessName(name) || VersioningProfile.hasTerminalVersion(name)) {
			return false;
		}
		int versionComponent = VersioningProfile.findLastVersionComponent(name);
		if (name.stringComponent(versionComponent - 1).equals(DATA_KEY_NAME)) {
			return true;
		}
		return false;
	}

}
