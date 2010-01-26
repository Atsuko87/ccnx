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

package org.ccnx.ccn.profiles;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.ContentVerifier;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.impl.support.DataUtils.Tuple;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.ExcludeAny;
import org.ccnx.ccn.protocol.ExcludeComponent;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;


/**
 * Versions, when present, usually occupy the penultimate component of the CCN name, 
 * not counting the digest component. A name may actually incorporate multiple
 * versions, where the rightmost version is the version of "this" object, if it
 * has one, and previous (parent) versions are the versions of the objects of
 * which this object is a part. The most common location of a version, if present,
 * is in the next to last component of the name, where the last component is a
 * segment number (which is generally always present; versions themselves are
 * optional). More complicated segmentation profiles occur, where a versioned
 * object has components that are structured and named in ways other than segments --
 * and may themselves have individual versions (e.g. if the components of such
 * a composite object are written as CCNNetworkObjects and automatically pick
 * up an (unnecessary) version in their own right). Versioning operations therefore
 * take context from their caller about where to expect to find a version,
 * and attempt to ignore other versions in the name.
 * 
 * Versions may be chosen based on time.
 * The first byte of the version component is 0xFD. The remaining bytes are a
 * big-endian binary number. If based on time they are expressed in units of
 * 2**(-12) seconds since the start of Unix time, using the minimum number of
 * bytes. The time portion will thus take 48 bits until quite a few centuries
 * from now (Sun, 20 Aug 4147 07:32:16 GMT). With 12 bits of precision, it allows 
 * for sub-millisecond resolution. The client generating the version stamp 
 * should try to avoid using a stamp earlier than (or the same as) any 
 * version of the file, to the extent that it knows about it. It should 
 * also avoid generating stamps that are unreasonably far in the future.
 */
public class VersioningProfile implements CCNProfile {

	public static final byte VERSION_MARKER = (byte)0xFD;
	public static final byte [] FIRST_VERSION_MARKER = new byte []{VERSION_MARKER};
	public static final byte FF = (byte) 0xFF;
	public static final byte OO = (byte) 0x00;

	public static final int GET_LATEST_VERSION_ATTEMPTS = 10;
	
	/**
	 * Add a version field to a ContentName.
	 * @return ContentName with a version appended. Does not affect previous versions.
	 */
	public static ContentName addVersion(ContentName name, long version) {
		// Need a minimum-bytes big-endian representation of version.
		byte [] vcomp = null;
		if (0 == version) {
			vcomp = FIRST_VERSION_MARKER;
		} else {
			byte [] varr = BigInteger.valueOf(version).toByteArray();
			vcomp = new byte[varr.length + 1];
			vcomp[0] = VERSION_MARKER;
			System.arraycopy(varr, 0, vcomp, 1, varr.length);
		}
		return new ContentName(name, vcomp);
	}
	
	/**
	 * Converts a timestamp into a fixed point representation, with 12 bits in the fractional
	 * component, and adds this to the ContentName as a version field. The timestamp is rounded
	 * to the nearest value in the fixed point representation.
	 * <p>
	 * This allows versions to be recorded as a timestamp with a 1/4096 second accuracy.
	 * @see #addVersion(ContentName, long)
	 */
	public static ContentName addVersion(ContentName name, CCNTime version) {
		if (null == version)
			throw new IllegalArgumentException("Version cannot be null!"); 
		byte [] vcomp = timeToVersionComponent(version);
		return new ContentName(name, vcomp);
	}
	
	/**
	 * Add a version field based on the current time, accurate to 1/4096 second.
	 * @see #addVersion(ContentName, CCNTime)
	 */
	public static ContentName addVersion(ContentName name) {
		return addVersion(name, CCNTime.now());
	}
	
	public static byte [] timeToVersionComponent(CCNTime version) {
		byte [] varr = version.toBinaryTime();
		byte [] vcomp = new byte[varr.length + 1];
		vcomp[0] = VERSION_MARKER;
		System.arraycopy(varr, 0, vcomp, 1, varr.length);
		return vcomp;
	}
	
	public static String printAsVersionComponent(CCNTime version) {
		byte [] vcomp = timeToVersionComponent(version);
		return ContentName.componentPrintURI(vcomp);
	}
	
	/**
	 * Adds a version to a ContentName; if there is a terminal version there already,
	 * first removes it.
	 */
	public static ContentName updateVersion(ContentName name, long version) {
		return addVersion(cutTerminalVersion(name).first(), version);
	}
	
	/**
	 * Adds a version to a ContentName; if there is a terminal version there already,
	 * first removes it.
	 */
	public static ContentName updateVersion(ContentName name, CCNTime version) {
		return addVersion(cutTerminalVersion(name).first(), version);
	}

	/**
	 * Add updates the version field based on the current time, accurate to 1/4096 second.
	 * @see #updateVersion(ContentName, Timestamp)
	 */
	public static ContentName updateVersion(ContentName name) {
		return updateVersion(name, CCNTime.now());
	}

	/**
	 * Finds the last component that looks like a version in name.
	 * @param name
	 * @return the index of the last version component in the name, or -1 if there is no version
	 *					component in the name
	 */
	public static int findLastVersionComponent(ContentName name) {
		int i = name.count();
		for (;i >= 0; i--)
			if (isVersionComponent(name.component(i)))
				return i;
		return -1;
	}

	/**
	 * Checks to see if this name has a validly formatted version field anywhere in it.
	 */
	public static boolean containsVersion(ContentName name) {
		return findLastVersionComponent(name) != -1;
	}
	
	/**
	 * Checks to see if this name has a validly formatted version field either in final
	 * component or in next to last component with final component being a segment marker.
	 */
	public static boolean hasTerminalVersion(ContentName name) {
		if ((name.count() > 0) && 
			((isVersionComponent(name.lastComponent()) || 
			 ((name.count() > 1) && SegmentationProfile.isSegment(name) && isVersionComponent(name.component(name.count()-2)))))) {
			return true;
		}
		return false;
	}
	
	/**
	 * Check a name component to see if it is a valid version field
	 */
	public static boolean isVersionComponent(byte [] nameComponent) {
		return (null != nameComponent) && (0 != nameComponent.length) && 
			   (VERSION_MARKER == nameComponent[0]) && 
			   ((nameComponent.length == 1) || (nameComponent[1] != 0));
	}
	
	public static boolean isBaseVersionComponent(byte [] nameComponent) {
		return (isVersionComponent(nameComponent) && (1 == nameComponent.length));
	}
	
	/**
	 * Remove a terminal version marker (one that is either the last component of name, or
	 * the next to last component of name followed by a segment marker) if one exists, otherwise
	 * return name as it was passed in.
	 * @param name
	 * @return
	 */
	public static Tuple<ContentName, byte[]> cutTerminalVersion(ContentName name) {
		if (name.count() > 0) {
			if (isVersionComponent(name.lastComponent())) {
				return new Tuple<ContentName, byte []>(name.parent(), name.lastComponent());
			} else if ((name.count() > 2) && SegmentationProfile.isSegment(name) && isVersionComponent(name.component(name.count()-2))) {
				return new Tuple<ContentName, byte []>(name.cut(name.count()-2), name.component(name.count()-2));
			}
		}
		return new Tuple<ContentName, byte []>(name, null);
	}
	
	/**
	 * Take a name which may have one or more version components in it,
	 * and strips the last one and all following components. If no version components
	 * present, returns the name as handed in.
	 */
	public static ContentName cutLastVersion(ContentName name) {
		int offset = findLastVersionComponent(name);
		return (offset == -1) ? name : new ContentName(offset, name.components());
	}

	/**
	 * Function to get the version field as a long.  Starts from the end and checks each name component for the version marker.
	 * @param name
	 * @return long
	 * @throws VersionMissingException
	 */
	public static long getLastVersionAsLong(ContentName name) throws VersionMissingException {
		int i = findLastVersionComponent(name);
		if (i == -1)
			throw new VersionMissingException();
		
		return getVersionComponentAsLong(name.component(i));
	}
	
	public static long getVersionComponentAsLong(byte [] versionComponent) {
		byte [] versionData = new byte[versionComponent.length - 1];
		System.arraycopy(versionComponent, 1, versionData, 0, versionComponent.length - 1);
		if (versionData.length == 0)
			return 0;
		return new BigInteger(versionData).longValue();
	}

	public static CCNTime getVersionComponentAsTimestamp(byte [] versionComponent) {
		if (null == versionComponent)
			return null;
		return versionLongToTimestamp(getVersionComponentAsLong(versionComponent));
	}

	/**
	 * Extract the version from this name as a Timestamp.
	 * @throws VersionMissingException 
	 */
	public static CCNTime getLastVersionAsTimestamp(ContentName name) throws VersionMissingException {
		long time = getLastVersionAsLong(name);
		return CCNTime.fromBinaryTimeAsLong(time);
	}
	
	/**
	 * Returns null if no version, otherwise returns the last version in the name. 
	 * @param name
	 * @return
	 */
	public static CCNTime getLastVersionAsTimestampIfVersioned(ContentName name) {
		int versionComponent = findLastVersionComponent(name);
		if (versionComponent < 0)
			return null;
		return getVersionComponentAsTimestamp(name.component(versionComponent));
	}
	
	public static CCNTime getTerminalVersionAsTimestampIfVersioned(ContentName name) {
		if (!hasTerminalVersion(name))
			return null;
		int versionComponent = findLastVersionComponent(name);
		if (versionComponent < 0)
			return null;
		return getVersionComponentAsTimestamp(name.component(versionComponent));
	}
	
	public static CCNTime versionLongToTimestamp(long version) {
		return CCNTime.fromBinaryTimeAsLong(version);
	}
	
	/**
	 * Control whether versions start at 0 or 1.
	 * @return
	 */
	public static final int baseVersion() { return 0; }

	/**
	 * Compares terminal version (versions at the end of, or followed by only a segment
	 * marker) of a name to a given timestamp.
	 * @param left
	 * @param right
	 * @return
	 */
	public static int compareVersions(
			CCNTime left,
			ContentName right) {
		if (!hasTerminalVersion(right)) {
			throw new IllegalArgumentException("Both names to compare must be versioned!");
		}
		try {
			return left.compareTo(getLastVersionAsTimestamp(right));
		} catch (VersionMissingException e) {
			throw new IllegalArgumentException("Name that isVersioned returns true for throws VersionMissingException!: " + right);
		}
	}
	
	public static int compareVersionComponents(
			byte [] left,
			byte [] right) throws VersionMissingException {
		// Propagate correct exception to callers.
		if ((null == left) || (null == right))
			throw new VersionMissingException("Must compare two versions!");
		// DKS TODO -- should be able to just compare byte arrays, but would have to check version
		return getVersionComponentAsTimestamp(left).compareTo(getVersionComponentAsTimestamp(right));
	}
	
	/**
	 * See if version is a version of parent (not commutative).
	 * @return
	 */
	public static boolean isVersionOf(ContentName version, ContentName parent) {
		Tuple<ContentName, byte []>versionParts = cutTerminalVersion(version);
		if (!parent.equals(versionParts.first())) {
			return false; // not versions of the same thing
		}
		if (null == versionParts.second())
			return false; // version isn't a version
		return true;
    }
	
	/**
	 * This compares two names, with terminal versions, and determines whether one is later than the other.
	 * @param laterVersion
	 * @param earlierVersion
	 * @return
	 * @throws VersionMissingException
	 */
	public static boolean isLaterVersionOf(ContentName laterVersion, ContentName earlierVersion) throws VersionMissingException {
		// TODO -- remove temporary warning
		Log.warning("SEMANTICS CHANGED: if experiencing unexpected behavior, check to see if you want to call isLaterVerisionOf or startsWithLaterVersionOf");
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(earlierVersion);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(laterVersion);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			return false; // not versions of the same thing
		}
		return (compareVersionComponents(laterVersionParts.second(), earlierVersionParts.second()) > 0);
    }
	
	/**
	 * Finds out if you have a versioned name, and a ContentObject that might have a versioned name which is 
	 * a later version of the given name, even if that CO name might not refer to a segment of the original name.
	 * For example, given a name /parc/foo.txt/<version1> or /parc/foo.txt/<version1>/<segment>
	 * and /parc/foo.txt/<version2>/<stuff>, return true, whether <stuff> is a segment marker, a whole
	 * bunch of repo write information, or whatever. 
	 * @param newName Will check to see if this name begins with something which is a later version of previousVersion.
	 * @param previousVersion The name to compare to, must have a terminal version or be unversioned.
	 * @return
	 */
	public static boolean startsWithLaterVersionOf(ContentName newName, ContentName previousVersion) {
		// If no version, treat whole name as prefix and any version as a later version.
		Tuple<ContentName, byte []>previousVersionParts = cutTerminalVersion(previousVersion);
		if (!previousVersionParts.first().isPrefixOf(newName))
			return false;
		if (null == previousVersionParts.second()) {
			return ((newName.count() > previousVersionParts.first().count()) && 
					VersioningProfile.isVersionComponent(newName.component(previousVersionParts.first().count())));
		}
		try {
			return (compareVersionComponents(newName.component(previousVersionParts.first().count()), previousVersionParts.second()) > 0);
		} catch (VersionMissingException e) {
			return false; // newName doesn't have to have a version there...
		}
	}

	public static int compareTerminalVersions(ContentName laterVersion, ContentName earlierVersion) throws VersionMissingException {
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(earlierVersion);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(laterVersion);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			throw new IllegalArgumentException("Names not versions of the same name!");
		}
		return (compareVersionComponents(laterVersionParts.second(), earlierVersionParts.second()));
    }

	/**
	 * Builds an Exclude filter that excludes components before or @ start, and components after
	 * the last valid version.
	 * @param startingVersionComponent The latest version component we know about. Can be null or
	 * 			VersioningProfile.isBaseVersionComponent() == true to indicate that we want to start
	 * 			from 0 (we don't have a known version we're trying to update). This exclude filter will
	 * 			find versions *after* the version represented in startingVersionComponent.
	 * @return An exclude filter.
	 */
	public static Exclude acceptVersions(byte [] startingVersionComponent) {
		byte [] start = null;
		// initially exclude name components just before the first version, whether that is the
		// 0th version or the version passed in
		if ((null == startingVersionComponent) || VersioningProfile.isBaseVersionComponent(startingVersionComponent)) {
			start = new byte [] { VersioningProfile.VERSION_MARKER, VersioningProfile.OO, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF };
		} else {
			start = startingVersionComponent;
		}
		
		ArrayList<Exclude.Element> ees = new ArrayList<Exclude.Element>();
		ees.add(new ExcludeAny());
		ees.add(new ExcludeComponent(start));
		ees.add(new ExcludeComponent(new byte [] {
				VERSION_MARKER+1, OO, OO, OO, OO, OO, OO } ));
		ees.add(new ExcludeAny());
		
		return new Exclude(ees);
	}
	
	/**
	 * Active methods. Want to provide profile-specific methods that:
	 * - find the latest version without regard to what is below it
	 * 		- if no version given, gets the latest version
	 * 		- if a starting version given, gets the latest version available *after* that version;
	 *            will time out if no such newer version exists
	 *    Returns a content object, which may or may not be a segment of the latest version, but the
	 *    latest version information is available from its name.
	 *    
	 * - find the first segment of the latest version of a name
	 * 		- if no version given, gets the first segment of the latest version
	 * 		- if a starting version given, gets the latest version available *after* that version or times out
	 *    Will ensure that what it returns is a segment of a version of that object.
	 *    
	 * - generate an interest designed to find the first segment of the latest version
	 *   of a name, in the above form; caller is responsible for checking and re-issuing
	 */

	/**
	 * Generate an interest that will find the leftmost child of the latest version. It
	 * will ensure that the next to last segment is a version, and the last segment (excluding
	 * digest) is the leftmost child available. But it can't guarantee that the latter is
	 * a segment. Because most data is segmented, length constraints will make it very
	 * likely, however.
	 * @param startingVersion
	 * @return
	 */
	public static Interest firstBlockLatestVersionInterest(ContentName startingVersion, PublisherPublicKeyDigest publisher) {
		// by the time we look for extra components we will have a version on our name if it
		// doesn't have one already, so look for names with 2 extra components -- segment and digest.
		return latestVersionInterest(startingVersion, 3, publisher);
	}
	
	/**
	 * Generate an interest that will find a descendant of the latest version of startingVersion,
	 * after any existing version component. If additionalNameComponents is non-null, it will
	 * find a descendant with exactly that many name components after the version (including
	 * the digest). The latest version is the rightmost child of the desired prefix, however,
	 * this interest will find leftmost descendants of that rightmost child. With appropriate
	 * length limitations, can be used to find segments of the latest version (though that
	 * will work more effectively with appropriate segment numbering).
	 */
	public static Interest latestVersionInterest(ContentName startingVersion, Integer additionalNameComponents, PublisherPublicKeyDigest publisher) {
		
		if (hasTerminalVersion(startingVersion)) {
			// Has a version. Make sure it doesn't have a segment; find a version after this one.
			startingVersion = SegmentationProfile.segmentRoot(startingVersion);
		} else {
			// Doesn't have a version. Add the "0" version, so we are finding any version after that.
			ContentName firstVersionName = addVersion(startingVersion, baseVersion());
			startingVersion = firstVersionName;
		}
		byte [] versionComponent = startingVersion.lastComponent();
		
		Interest constructedInterest = Interest.last(startingVersion, acceptVersions(versionComponent), startingVersion.count() - 1, additionalNameComponents, 
					additionalNameComponents, null);
		if (null != publisher) {
			constructedInterest.publisherID(new PublisherID(publisher));
		}
		return constructedInterest;
	}

	/**
	 * Gets the latest version using a single interest/response. There may be newer versions available
	 * if you ask again passing in the version found (i.e. each response will be the latest version
	 * a given responder knows about. Further queries will move past that responder to other responders,
	 * who may have newer information.)
	 *  
	 * @param name If the name ends in a version then this method explicitly looks for a newer version
	 * than that, and will time out if no such later version exists. If the name does not end in a 
	 * version then this call just looks for the latest version.
	 * @param publisher Currently unused, will limit query to a specific publisher.
	 * @param timeout
	 * @return A ContentObject with the latest version, or null if the query timed out. Note - the content
	 * returned could be any name under this new version - by default it will get the leftmost item,
	 * but right now that is generally a repo start write, not a segment. Changing the marker values
	 * used will fix that.
	 * @result Returns a matching ContentObject, *unverified*.
	 * @throws IOException
	 */
	public static ContentObject getLatestVersion(ContentName startingVersion, 
											     PublisherPublicKeyDigest publisher, 
												 long timeout, 
 												 ContentVerifier verifier,
												 CCNHandle handle) throws IOException {
		
		return getLatestVersion(startingVersion, publisher, timeout, verifier, handle, null, false);
		/*
		ContentName latestVersionFound = startingVersion;
		
		while (true) {
			
			Interest getLatestInterest = latestVersionInterest(latestVersionFound, null, publisher);
			ContentObject co = handle.get(getLatestInterest, timeout);
			if (co == null) {
				Log.info("Null returned from getLatest for name: " + startingVersion);
				return null;
			}
			// What we get should be a block representing a later version of name. It might
			// be an actual segment of a versioned object, but it might also be an ancillary
			// object - e.g. a repo message -- which starts with a particular version of name.
			if (startsWithLaterVersionOf(co.name(), startingVersion)) {
				// we got a valid version! 
				Log.info("Got latest version: " + co.name());
				// Now need to verify the block we got
				if (verifier.verify(co)) {
					return co;
				}
				Log.warning("VERIFICATION FAILURE: " + co.name() + ", need to find better way to decide what to do next.");
			} else {
				Log.info("Rejected potential candidate version: " + co.name() + " not a later version of " + startingVersion);
			}
			latestVersionFound = new ContentName(getLatestInterest.name().count(), co.name().components());
		}
		*/
	}
	
	private static ContentObject getLatestVersion(ContentName startingVersion, 
												  PublisherPublicKeyDigest publisher,
												  long timeout,
												  ContentVerifier verifier,
												  CCNHandle handle,
												  Long startingSegmentNumber,
												  boolean getFirstSegment) throws IOException {
		
		Log.info("getFirstBlockOfLatestVersion: getting version later than " + startingVersion);
		
		System.out.println("called with timeout: "+timeout);
		
		int attempts = 0;
		//long attemptTimeout = SystemConfiguration.SHORT_TIMEOUT;
		long attemptTimeout = SystemConfiguration.MEDIUM_TIMEOUT;
		if (timeout == SystemConfiguration.NO_TIMEOUT) {
			//the timeout sent in is equivalent to null...  try till we don't hear something back
			//we will reset the remaining time after each return...
		} else if (timeout > 0 && timeout < attemptTimeout) {
			attemptTimeout = timeout;
		}
			
		long nullTimeout = attemptTimeout;
		
		if( timeout > attemptTimeout)
			nullTimeout = timeout;
		
		long startTime;
		long respondTime;
		long remainingTime = attemptTimeout;
		long remainingNullTime = nullTimeout; 
		
		ContentName prefix = startingVersion;
		if (hasTerminalVersion(prefix)) {
			prefix = startingVersion.parent();
		}
		int versionedLength = prefix.count() + 1;
		
		ContentObject result = null;
		ContentObject lastResult = null;
		
		Exclude excludes = null;
		ArrayList<byte[]> excludeList = new ArrayList<byte[]>();
		
		while (attempts < GET_LATEST_VERSION_ATTEMPTS && remainingTime > 0) {
			System.out.println("attempts: "+attempts+" attemptTimeout: "+attemptTimeout+" remainingTime: "+remainingTime+" (timeout: "+timeout+")");
			lastResult = result;
			attempts++;
			Interest getLatestInterest = null;
			if (getFirstSegment) {
				getLatestInterest = firstBlockLatestVersionInterest(startingVersion, publisher);
			} else {
				getLatestInterest = latestVersionInterest(startingVersion, null, publisher);
			}
			
			if (excludeList.size() > 0) {
				//we have explicit excludes, add them to this interest
				byte [][] e = new byte[excludeList.size()][];
				excludeList.toArray(e);
				getLatestInterest.exclude().add(e);
			}
			
			
			System.out.println("INTEREST: "+getLatestInterest);
			System.out.println("trying handle.get with timeout: "+attemptTimeout);
			startTime = System.currentTimeMillis();
			System.out.println("sending Interest from gLV at "+System.currentTimeMillis());
			result = handle.get(getLatestInterest, attemptTimeout);
			respondTime = System.currentTimeMillis() - startTime;
			System.out.println("returned from handle.get in "+respondTime+" ms");
			remainingTime = remainingTime - respondTime;
			remainingNullTime = remainingNullTime - respondTime;
			System.out.println("remaining time is now "+remainingTime+"ms");
			if (null != result){
				System.out.println("we got something back...");
				Log.info("getFirstBlockOfLatestVersion: retrieved latest version object " + result.name() + " type: " + result.signedInfo().getTypeName());
			
				//did it verify?
				//if it doesn't verify, we need to try harder to get a different content object (exclude this digest)
				//make this a loop?
				if (!verifier.verify(result)) {
					//excludes = addVersionToExcludes(excludes, result.name());
					System.out.println("result did not verify, trying to find a verifiable answer");
					excludeList = addVersionToExcludes(excludeList, result.name());
					
					Interest retry = new Interest(SegmentationProfile.segmentRoot(result.name()), publisher);
					retry.maxSuffixComponents(1);
					boolean verifyDone = false;
					while(!verifyDone) {
						if(retry.exclude() == null)
							retry.exclude(new Exclude());
						retry.exclude().add(new byte[][] {result.digest()});
						System.out.println("result did not verify!  doing retry!! "+retry.toString());
						System.out.println("sending retry interest at "+System.currentTimeMillis());
						result = handle.get(retry, attemptTimeout);
						
						if (result!=null) {
							System.out.println("we got something back: "+result.name());
							if(verifier.verify(result)) {
								System.out.println("the returned answer verifies");
								verifyDone = true;
							} else {
								System.out.println("this answer did not verify either...  try again");
								
							}
						} else {
							//result is null, we didn't find a verifiable answer
							System.out.println("did not get a verifiable answer back");
							verifyDone = true;
						}
					}	
					//TODO  if this is the latest version and we exclude it, we might not have anything to send back...  we should reset the starting version
				} 
				if (result!=null) {
					//else {
					//it verified!  are we done?
					
					//first check if we need to get the first segment...
					if (getFirstSegment) {
						//yes, we need to have the first segment....
						// Now we know the version. Did we luck out and get first block?
						if (VersioningProfile.isVersionedFirstSegment(prefix, result, startingSegmentNumber)) {
							Log.info("getFirstBlockOfLatestVersion: got first block on first try: " + result.name());
						} else {
							//not the first segment...
							
							// This isn't the first block. Might be simply a later (cached) segment, or might be something
							// crazy like a repo_start_write. So what we want is to get the version of this new block -- if getLatestVersion
							// is doing its job, we now know the version we want (if we already knew that, we called super.getFirstBlock
							// above. If we get here, _baseName isn't versioned yet. So instead of taking segmentRoot of what we got,
							// which works fine only if we have the wrong segment rather than some other beast entirely (like metadata).
							// So chop off the new name just after the (first) version, and use that. If getLatestVersion is working
							// right, that should be the right thing.
							ContentName notFirstBlockVersion = result.name().cut(versionedLength);
							Log.info("CHILD SELECTOR FAILURE: getFirstBlockOfLatestVersion: Have version information, now querying first segment of " + startingVersion);
							// this will verify
							
							//don't count this against the gLV timeout.
							
							result = SegmentationProfile.getSegment(notFirstBlockVersion, startingSegmentNumber, null, timeout, verifier, handle); // now that we have the latest version, go back for the first block.
							//if this isn't the first segment...  then we should exclude it.  otherwise, we can use it!
							if(result == null) {
								//we couldn't get a new segment...
								System.out.println("could not get the first segment of the version we just found...  should exclude the version");
								//excludes = addVersionToExcludes(excludes, startingVersion);
								excludeList = addVersionToExcludes(excludeList, notFirstBlockVersion);
							}
						}
						
						
					} else {
						//no need to get the first segment!
						//this is already verified!
					}
					
					//if result is not null, we really have something to try since it also verified
					if (result != null) {
					
						//this could be our answer...  set to lastResult and see if we have time to do better
						lastResult = result;
					
						if (timeout == SystemConfiguration.NO_TIMEOUT) {
							//we want to keep trying for something new
							remainingTime = attemptTimeout;
							attempts = 0;
						}
						
						if (timeout == 0) {
							//caller just wants the first answer...
							attempts = GET_LATEST_VERSION_ATTEMPTS;
							remainingTime = 0;
						}
						
						if (remainingTime > 0) {
							//we still have time to try for a better answer
							System.out.println("we still have time to try for a better answer");
							attemptTimeout = remainingTime;
						} else {
							System.out.println("time is up, return what we have");
							attempts = GET_LATEST_VERSION_ATTEMPTS;
						}
					
						
					} else {
						//result is null
						//will be handled below
					}
				}//the result verified
			} //we got something back
			
			if (result == null) {
				System.out.println("we didn't get anything");
				Log.info("getFirstBlockOfLatestVersion: no block available for later version of " + startingVersion);
				//we didn't get a new version...  we can return the last one we received if it isn't null.
				if (lastResult!=null) {
					System.out.println("returning the last result that wasn't null... ");
					System.out.println("returning: "+lastResult.name());
					return lastResult;
				}
				else {
					System.out.println("we didn't get anything, and we haven't had anything at all... try with remaining long timeout");
					attemptTimeout = remainingNullTime;
					remainingTime = remainingNullTime;
				}
			}
			System.out.println("(after) attempts: "+attempts+" attemptTimeout: "+attemptTimeout+" remainingTime: "+remainingTime+" (timeout: "+timeout+")");
			if (result!=null)
				startingVersion = SegmentationProfile.segmentRoot(result.name());
		}
		if(result!=null)
			System.out.println("returning: "+result.name());
		return result;
	}
	
	
	/**
	 * - find the first segment of the latest version of a name
	 * 		- if no version given, gets the first segment of the latest version
	 * 		- if a starting version given, gets the latest version available *after* that version or times out
	 *    Will ensure that what it returns is a segment of a version of that object.
	 *	 * @param desiredName The name of the object we are looking for the first segment of.
	 * 					  If (VersioningProfile.hasTerminalVersion(desiredName) == false), will get latest version it can
	 * 							find of desiredName.
	 * 					  If desiredName has a terminal version, will try to find the first block of content whose
	 * 						    version is *after* desiredName (i.e. getLatestVersion starting from desiredName).
	 * @param startingSegmentNumber The desired block number, or SegmentationProfile.baseSegment if null.
	 * @param publisher, if one is specified.
	 * @param timeout
	 * @return The first block of a stream with a version later than desiredName, or null if timeout is reached.
	 *   		This block is *unverified*.
	 * @throws IOException
	 */
	public static ContentObject getFirstBlockOfLatestVersion(ContentName startingVersion, 
															 Long startingSegmentNumber, 
															 PublisherPublicKeyDigest publisher, 
															 long timeout, 
															 ContentVerifier verifier,
															 CCNHandle handle) throws IOException {
		
		return getLatestVersion(startingVersion, publisher, timeout, verifier, handle, startingSegmentNumber, true);
		
		/*
		Log.info("getFirstBlockOfLatestVersion: getting version later than " + startingVersion);
		
		System.out.println("called with timeout: "+timeout);
		
		int attempts = 0;
		long attemptTimeout = SystemConfiguration.SHORT_TIMEOUT;
		if (timeout == SystemConfiguration.NO_TIMEOUT) {
			//the timeout sent in is equivalent to null...  try till we don't hear something back
			//we will reset the remaining time after each return...
		} else if (timeout > 0 && timeout < attemptTimeout) {
			attemptTimeout = timeout;
		}
			
		long nullTimeout = attemptTimeout;
		
		if( timeout > attemptTimeout)
			nullTimeout = timeout;
		
		long startTime;
		long respondTime;
		long remainingTime = attemptTimeout;
		long remainingNullTime = nullTimeout; 
		
		ContentName prefix = startingVersion;
		if (hasTerminalVersion(prefix)) {
			prefix = startingVersion.parent();
		}
		int versionedLength = prefix.count() + 1;
		
		ContentObject result = null;
		ContentObject lastResult = null;
		
		Exclude excludes = new Exclude(); 
		
		while (attempts < GET_LATEST_VERSION_ATTEMPTS && remainingTime > 0) {
			System.out.println("attempts: "+attempts+" attemptTimeout: "+attemptTimeout+" remainingTime: "+remainingTime+" (timeout: "+timeout+")");
			lastResult = result;
			attempts++;
			Interest getLatestInterest = firstBlockLatestVersionInterest(startingVersion, publisher);
			System.out.println("trying handle.get with timeout: "+attemptTimeout);
			startTime = System.currentTimeMillis();
			result = handle.get(getLatestInterest, attemptTimeout);
			respondTime = System.currentTimeMillis() - startTime;
			System.out.println("returned from handle.get in "+respondTime+" ms");
			remainingTime = remainingTime - respondTime;
			remainingNullTime = remainingNullTime - respondTime;
			System.out.println("remaining time is now "+remainingTime+"ms");
			if (null != result){
				System.out.println("we got something back...");
				Log.info("getFirstBlockOfLatestVersion: retrieved latest version object " + result.name() + " type: " + result.signedInfo().getTypeName());
			
				// Now we know the version. Did we luck out and get first block?
				if (VersioningProfile.isVersionedFirstSegment(prefix, result, startingSegmentNumber)) {
					Log.info("getFirstBlockOfLatestVersion: got first block on first try: " + result.name());
					// Now need to verify the block we got
					if (!verifier.verify(result)) {
						try {
							excludes.add(new byte[][] {VersioningProfile.addVersion(new ContentName(),VersioningProfile.getLastVersionAsLong(result.name())).component(0)});
							System.out.println("was able to exclude: "+excludes.toString());
						} catch (VersionMissingException e) {
							Log.warning("failed to exclude content object version that did not verify: {0}",result.name());
						}
							
						// TODO rework to allow retries
						Log.info("Block failed to verify! Need to robustify method!");
					} else {
						//this result verified!
					
						//this could be our answer...  set to lastResult and see if we have time to do better
						lastResult = result;
					
						if (remainingTime > 0) {
							//we still have time to try for a better answer
							System.out.println("we still have time to try for a better answer");
						} else {
							System.out.println("time is up, return what we have");
							attempts = GET_LATEST_VERSION_ATTEMPTS;
						}
					
						if (timeout == SystemConfiguration.NO_TIMEOUT) {
							//we want to keep trying for something new
							remainingTime = attemptTimeout;
							attempts = 0;
						}
					}
					//return result;
				} else {
					// This isn't the first block. Might be simply a later (cached) segment, or might be something
					// crazy like a repo_start_write. So what we want is to get the version of this new block -- if getLatestVersion
					// is doing its job, we now know the version we want (if we already knew that, we called super.getFirstBlock
					// above. If we get here, _baseName isn't versioned yet. So instead of taking segmentRoot of what we got,
					// which works fine only if we have the wrong segment rather than some other beast entirely (like metadata).
					// So chop off the new name just after the (first) version, and use that. If getLatestVersion is working
					// right, that should be the right thing.
					startingVersion = result.name().cut(versionedLength);
					Log.info("CHILD SELECTOR FAILURE: getFirstBlockOfLatestVersion: Have version information, now querying first segment of " + startingVersion);
					// this will verify
					
					//don't count this against the gLV timeout.
					
					result = SegmentationProfile.getSegment(startingVersion, startingSegmentNumber, null, timeout, verifier, handle); // now that we have the latest version, go back for the first block.
					//return result;
				}
			} else {
				System.out.println("we didn't get anything");
				Log.info("getFirstBlockOfLatestVersion: no block available for later version of " + startingVersion);
				//we didn't get a new version...  we can return the last one we received if it isn't null.
				if (lastResult!=null)
					return lastResult;
				else {
					System.out.println("we didn't get anything, and we haven't had anything at all... try with remaining long timeout");
					attemptTimeout = remainingNullTime;
					remainingTime = remainingNullTime;
				}
			}
			System.out.println("(after) attempts: "+attempts+" attemptTimeout: "+attemptTimeout+" remainingTime: "+remainingTime+" (timeout: "+timeout+")");
			if (result!=null)
				startingVersion = SegmentationProfile.segmentRoot(result.name());
		}
		return result;
		*/
	}

	/**
	 * Version of isFirstSegment that expects names to be versioned, and allows that desiredName
	 * won't know what version it wants but will want some version.
	 */
	public static boolean isVersionedFirstSegment(ContentName desiredName, ContentObject potentialFirstSegment, Long startingSegmentNumber) {
		if ((null != potentialFirstSegment) && (SegmentationProfile.isSegment(potentialFirstSegment.name()))) {
			Log.info("is " + potentialFirstSegment.name() + " a first segment of " + desiredName);
			// In theory, the segment should be at most a versioning component different from desiredName.
			// In the case of complex segmented objects (e.g. a KeyDirectory), where there is a version,
			// then some name components, then a segment, desiredName should contain all of those other
			// name components -- you can't use the usual versioning mechanisms to pull first segment anyway.
			if (!desiredName.isPrefixOf(potentialFirstSegment.name())) {
				Log.info("Desired name :" + desiredName + " is not a prefix of segment: " + potentialFirstSegment.name());
				return false;
			}
			int difflen = potentialFirstSegment.name().count() - desiredName.count();
			if (difflen > 2) {
				Log.info("Have " + difflen + " extra components between " + potentialFirstSegment.name() + " and desired " + desiredName);
				return false;
			}
			// Now need to make sure that if the difference is more than 1, that difference is
			// a version component.
			if ((difflen == 2) && (!isVersionComponent(potentialFirstSegment.name().component(potentialFirstSegment.name().count()-2)))) {
				Log.info("The " + difflen + " extra component between " + potentialFirstSegment.name() + " and desired " + desiredName + " is not a version.");
				
			}
			if ((null != startingSegmentNumber) && (SegmentationProfile.baseSegment() != startingSegmentNumber)) {
				return (startingSegmentNumber.equals(SegmentationProfile.getSegmentNumber(potentialFirstSegment.name())));
			} else {
				return SegmentationProfile.isFirstSegment(potentialFirstSegment.name());
			}
		}
		return false;
	}

	private static ArrayList<byte[]> addVersionToExcludes(ArrayList<byte[]> excludeList, ContentName name) {
		try {
			excludeList.add(VersioningProfile.addVersion(new ContentName(),VersioningProfile.getLastVersionAsLong(name)).component(0));

			//excludes.add(new byte[][] {VersioningProfile.addVersion(new ContentName(),VersioningProfile.getLastVersionAsLong(name)).component(0)});
			System.out.println("was able to exclude: "+excludeList.toString());
		} catch (VersionMissingException e) {
			Log.warning("failed to exclude content object version that did not verify: {0}",name);
		}
		return excludeList;
	}
	
}
