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

package org.ccnx.ccn.profiles.nameenum;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.bouncycastle.util.Arrays;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Blocking and background interface to name enumeration. This allows a caller to specify a prefix
 * under which to enumerate available children, and name enumeration to proceed in the background
 * for as long as desired, providing updates whenever new data is published.
 * Currently implemented as a wrapper around CCNNameEnumerator, will likely directly aggregate
 * name enumeration responses in the future.
 * 
 * @see CCNNameEnumerator
 * @see BasicNameEnumeratorListener
 */
public class EnumeratedNameList implements BasicNameEnumeratorListener {
	
	protected static final long CHILD_WAIT_INTERVAL = 1000;
	
	protected ContentName _namePrefix;
	protected CCNNameEnumerator _enumerator;
	protected BasicNameEnumeratorListener callback;
	// make these contain something other than content names when the enumerator has better data types
	protected SortedSet<ContentName> _children = new TreeSet<ContentName>();
	protected SortedSet<ContentName> _newChildren = null;
	protected Object _childLock = new Object();
	protected CCNTime _lastUpdate = null;
	
	/**
	 * Creates an EnumeratedNameList object
	 * 
	 * This constructor creates a new EnumeratedNameList object that will begin enumerating
	 * the children of the specified prefix.  The new EnumeratedNameList will use the CCNHandle passed
	 * in to the constructor, or create a new one using CCNHandle#open() if it is null.
	 *  
	 * @param  namePrefix the ContentName whose children we wish to list.
	 * @param  handle the CCNHandle object for sending interests and receiving content object responses.
	 */
	public EnumeratedNameList(ContentName namePrefix, CCNHandle handle) throws IOException {
		if (null == namePrefix) {
			throw new IllegalArgumentException("namePrefix cannot be null!");
		}
		if (null == handle) {
			try {
				handle = CCNHandle.open();
			} catch (ConfigurationException e) {
				throw new IOException("ConfigurationException attempting to open a handle: " + e.getMessage());
			}
		}
		_namePrefix = namePrefix;
		_enumerator = new CCNNameEnumerator(namePrefix, handle, this);
	}
	
	/**
	 * Method to return the ContentName used for enumeration.
	 * 
	 * @return ContentName returns the prefix under which we are enumerating children.
	 */
	public ContentName getName() { return _namePrefix; }
	
	/** 
	 * Cancels ongoing name enumeration. Previously-accumulated information about
	 * children of this name are still stored and available for use.
	 * 
	 * @return void
	 * */
	public void stopEnumerating() {
		_enumerator.cancelPrefix(_namePrefix);
	}
	
	/**
	 * First-come first-served interface to retrieve only new data from
	 * enumeration responses as it arrives.  This method blocks and
	 * waits for data, but grabs the new data for processing
	 * (thus removing it from every other listener), in effect handing the
	 * new children to the first consumer to wake up and makes the other
	 * ones go around again. Useful for assigning work to a thread pool,
	 * somewhat dangerous in other contexts -- if there is more than
	 * one waiter, many waiters can wait forever.
	 * 
	 * @param timeout maximum amount of time to wait, 0 to wait forever.
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that are new to us, or null if we reached the
	 *  timeout before new data arrived
	 */
	public SortedSet<ContentName> getNewData(long timeout) {
		SortedSet<ContentName> childArray = null;
		synchronized(_childLock) { // reentrant?
			while ((null == _children) || _children.size() == 0) {
				waitForNewData(timeout);
				if (timeout != SystemConfiguration.TIMEOUT_FOREVER)
					break;
			}
			Log.info("Waiting for new data on prefix: " + _namePrefix + " got " + ((null == _newChildren) ? 0 : _newChildren.size())
					+ ".");

			if (null != _newChildren) {
				childArray = _newChildren;
				_newChildren = null;
			}
		}
		return childArray;
	}
	
	/**
	 * Block and wait as long as it takes for new data to appear. See #getNewData(long).
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that are new to us, or null if we reached the
	 *  timeout before new data arrived
	 */
	public SortedSet<ContentName> getNewData() {
		return getNewData(SystemConfiguration.TIMEOUT_FOREVER);
	}
	
	/**
	 * Returns single-component ContentName objects containing the name components of the children.
	 * @return SortedSet<ContentName> Returns the array of single-component
	 * 	content name children that have been retrieved so far, or null if no responses
	 *  have yet been received. The latter may indicate either that no children of this prefix
	 *  are known to any responders, or that they have not had time to respond.
	 */
	public SortedSet<ContentName> getChildren() {
		if (!hasChildren())
			return null;
		return _children;
	}
	
	/**
	 * Returns true if the prefix has new names that have not been handled by the calling application.
	 * @return true if there are new children available to process
	 */
	public boolean hasNewData() {
		return ((null != _newChildren) && (_newChildren.size() > 0));
	}
	
	/**
	 * Returns true if we have received any responses listing available child names.
	 * If no names have yet been received, this may mean either that responses
	 * have not had time to arrive, or there are know children known to available
	 * responders.
	 * 
	 * @return true if we have child names received from enumeration responses
	 */
	public boolean hasChildren() {
		return ((null != _children) && (_children.size() > 0));
	}
	
	/**
	 * Returns true if we know the prefix has a child matching the given name component.
	 * 
	 * @param childComponent name component to check for in the stored child names.
	 * @return true if that child is in our list of known children
	 */
	public boolean hasChild(byte [] childComponent) {
		for (ContentName child : _children) {
			if (Arrays.areEqual(childComponent, child.component(0))) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Returns whether a child is present in the list of known children.
	 * <p>
	 * 
	 * @param childName String version of a child name to look for
	 * @return boolean Returns true if the name is present in the list of known children.
	 * */
	public boolean hasChild(String childName) {
		return hasChild(ContentName.componentParseNative(childName));
	}
	
	/**
	 * Wait for new children to arrive.
	 * 
	 * @param timeout Maximum time to wait for new data.
	 */
	public void waitForNewData(long timeout) {
		synchronized(_childLock) {
			CCNTime lastUpdate = _lastUpdate;
			long timeRemaining = timeout;
			long startTime = System.currentTimeMillis();
			while (((null == _lastUpdate) || ((null != lastUpdate) && !_lastUpdate.after(lastUpdate))) && 
				   ((timeout == SystemConfiguration.TIMEOUT_FOREVER) || (timeRemaining > 0))) {
				try {
					_childLock.wait((timeout != SystemConfiguration.TIMEOUT_FOREVER) ? Math.min(timeRemaining, CHILD_WAIT_INTERVAL) : CHILD_WAIT_INTERVAL);
					if (timeout != SystemConfiguration.TIMEOUT_FOREVER) {
						timeRemaining = timeout - (System.currentTimeMillis() - startTime);
					}
				} catch (InterruptedException e) {
				}
				Log.info("Waiting for new data on prefix: {0}, updated {1}, our update {2}, now have " + 
						((null == _children) ? 0 : _children.size()), _namePrefix + " new " + 
						((null == _newChildren) ? 0 : _newChildren.size()) + ".", _lastUpdate, lastUpdate);
			}
		}
	}	
	
	/**
	 * Wait for new children to arrive.
	 * This method does not have a timeout and will wait forever.
	 * 
	 * @return void
	 */
	public void waitForNewData() {
		waitForNewData(SystemConfiguration.TIMEOUT_FOREVER);
	}

	/**
	 * Waits until there is any data at all. Right now, waits for the first response containing actual
	 * children, not just a name enumeration response. That means it could block
	 * forever if no children exist in a repository or there are not any applications responding to
	 * name enumeration requests. Once we have an initial set of children, this method
	 * returns immediately.
	 * @param timeout Maximum amount of time to wait, if 0, waits forever.
	 * @return void
	 */
	public void waitForData(long timeout) {
		while ((null == _children) || _children.size() == 0) {
			waitForNewData(timeout);
			if (timeout != SystemConfiguration.TIMEOUT_FOREVER)
				break;
		}
	}
	
	/**
	 * Wait (block) for initial data to arrive, possibly forever. See #waitForData(long).
	 * 
	 * @return void
	 */
	public void waitForData() {
		waitForData(SystemConfiguration.TIMEOUT_FOREVER);
	}

	/**
	 * Handle responses from CCNNameEnumerator that give us a list of single-component child
	 * names. Filter out the names new to us, add them to our list of known children, postprocess
	 * them with processNewChildren(SortedSet<ContentName>), and signal waiters if we
	 * have new data.
	 * 
	 * @param prefix Prefix used for name enumeration.
	 * @param names The list of names returned in this name enumeration response.
	 * 
	 * @return int 
	 */
	public int handleNameEnumerator(ContentName prefix,
								    ArrayList<ContentName> names) {
		
		Log.info(names.size() + " new name enumeration results: our prefix: " + _namePrefix + " returned prefix: " + prefix);
		if (!prefix.equals(_namePrefix)) {
			Log.warning("Returned data doesn't match requested prefix!");
		}
		Log.info("Handling Name Iteration " + prefix +" ");
		// the name enumerator hands off names to us, we own it now
		// DKS -- want to keep listed as new children we previously had
		synchronized (_childLock) {
			TreeSet<ContentName> thisRoundNew = new TreeSet<ContentName>();
			thisRoundNew.addAll(names);
			Iterator<ContentName> it = thisRoundNew.iterator();
			while (it.hasNext()) {
				ContentName name = it.next();
				if (_children.contains(name)) {
					it.remove();
				}
			}
			if (!thisRoundNew.isEmpty()) {
				if (null != _newChildren) {
					_newChildren.addAll(thisRoundNew);
				} else {
					_newChildren = thisRoundNew;
				}
				_children.addAll(thisRoundNew);
				_lastUpdate = new CCNTime();
				Log.info("New children found: at {0} " + thisRoundNew.size() + " total children " + _children.size(), _lastUpdate);
				processNewChildren(thisRoundNew);
				_childLock.notifyAll();
			}
		}
		return 0;
	}
	
	/**
	 * Method to allow subclasses to do post-processing on incoming names
	 * before handing them to customers.
	 * Note that the set handed in here is not the set that will be handed
	 * out; only the name objects are the same.
	 * 
	 * @param newChildren SortedSet of children available for processing
	 * 
	 * @return void
	 */
	protected void processNewChildren(SortedSet<ContentName> newChildren) {
		// default -- do nothing.
	}

	/**
	 * If some or all of the children of this name are versions, returns the latest version
	 * among them.
	 * 
	 * @return ContentName The latest version component
	 * */
	public ContentName getLatestVersionChildName() {
		// of the available names in _children that are version components,
		// find the latest one (version-wise)
		// names are sorted, so the last one that is a version should be the latest version
		// ListIterator previous doesn't work unless you've somehow gotten it to point at the end...
		ContentName theName = null;
		ContentName latestName = null;
		CCNTime latestTimestamp = null;
		Iterator<ContentName> it = _children.iterator();
		// TODO these are sorted -- we just need to iterate through them in reverse order. Having
		// trouble finding something like C++'s reverse iterators to do that (linked list iterators
		// can go backwards -- but you have to run them to the end first).
		while (it.hasNext()) {
			theName = it.next();
			if (VersioningProfile.isVersionComponent(theName.component(0))) {
				if (null == latestName) {
					latestName = theName;
					latestTimestamp = VersioningProfile.getVersionComponentAsTimestamp(theName.component(0));
				} else {
					CCNTime thisTimestamp = VersioningProfile.getVersionComponentAsTimestamp(theName.component(0));
					if (thisTimestamp.after(latestTimestamp)) {
						latestName = theName;
						latestTimestamp = thisTimestamp;
					}
				}
			}
		}
		return latestName;
	}
	
	/**
	 * Returns the latest version available under this prefix as a CCNTime object.
	 * 
	 * @return CCNTime Latest child version as CCNTime
	 */
	public CCNTime getLatestVersionChildTime() {
		ContentName latestVersion = getLatestVersionChildName();
		if (null != latestVersion) {
			return VersioningProfile.getVersionComponentAsTimestamp(latestVersion.component(0));
		}
		return null;
	}

	/**
	 * A static method that performs a one-shot call that returns the complete name of the latest
	 * version of content with the given prefix. An alternative route to finding the name of the
	 * latest version of a piece of content, rather than using methods in the VersioningProfile
	 * to retrieve an arbitrary block of content under that version. Useful when the data under
	 * a version is complex in structure.
	 * 
	 * @param name ContentName to find the latest version of 
	 * @param handle CCNHandle to use for enumeration
	 * @return ContentName The name supplied to the call with the latest version added.
	 * @throws IOException
	 */
	public static ContentName getLatestVersionName(ContentName name, CCNHandle handle) throws IOException {
		EnumeratedNameList enl = new EnumeratedNameList(name, handle);
		enl.waitForData();
		ContentName childLatestVersion = enl.getLatestVersionChildName();
		enl.stopEnumerating();
		if (null != childLatestVersion) {
			return new ContentName(name, childLatestVersion.component(0));
		}
		return null;
	}

	/**
	 * Static method that iterates down the namespace starting with the supplied prefix
	 * as a ContentName (prefixKnownToExist) to a specific child (childName). The method
	 * returns null if the name does not exist in a limited time iteration.  If the child
	 * is found, this method returns the EnumeratedNameList object for the parent of the
	 * desired child in the namespace.  The current implementation may time out before the
	 * desired name is found.  Additionally, the current implementation does not loop on
	 * an enumeration attempt, so a child may be missed if it is not included in the first
	 * enumeration response.
	 * 
	 * TODO Add loop to enumerate under a name multiple times to avoid missing a child name
	 * TODO Handle timeouts better to avoid missing children.  (Note: We could modify the
	 * name enumeration protocol to return empty responses if we query for an unknown name,
	 *  but that adds semantic complications.)
	 *  
	 * @param childName ContentName for the child we are looking for under (does not have
	 * to be directly under) a given prefix.
	 * @param prefixKnownToExist ContentName prefix to enumerate to look for a given child.
	 * @param handle CCNHandle for sending and receiving interests and content objects.
	 * 
	 * @return EnumeratedNameList Returns the parent EnumeratedNameList for the desired child,
	 * if one is found.  Returns null if the child is not found.
	 * 
	 * @throws IOException 
	 */
	public static EnumeratedNameList exists(ContentName childName, ContentName prefixKnownToExist, CCNHandle handle) throws IOException {
		if ((null == prefixKnownToExist) || (null == childName) || (!prefixKnownToExist.isPrefixOf(childName))) {
			Log.info("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
			throw new IllegalArgumentException("Child " + childName + " must be prefixed by name " + prefixKnownToExist);
		}
		if (childName.count() == prefixKnownToExist.count()) {
			// we're already there
			return new EnumeratedNameList(childName, handle);
		}
		ContentName parentName = prefixKnownToExist;
		int childIndex = parentName.count();
		EnumeratedNameList parentEnumerator = null;
		while (childIndex < childName.count()) {
			parentEnumerator = new EnumeratedNameList(parentName, handle);
			parentEnumerator.waitForData(); // we're only getting the first round here... 
			// could wrap this bit in a loop if want to try harder
			if (parentEnumerator.hasChild(childName.component(childIndex))) {
				childIndex++;
				if (childIndex == childName.count()) {
					return parentEnumerator;
				}
				parentEnumerator.stopEnumerating();
				parentName = new ContentName(parentName, childName.component(childIndex));
				continue;
			} else {
				break;
			}
		}
		return null;
	}
}
