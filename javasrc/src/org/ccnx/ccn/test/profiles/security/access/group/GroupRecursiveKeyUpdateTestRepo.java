package org.ccnx.ccn.test.profiles.security.access.group;


import java.util.ArrayList;
import java.util.Random;

import junit.framework.Assert;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlManager;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.Group;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.profiles.security.TestUserData;
import org.junit.BeforeClass;
import org.junit.Test;

public class GroupRecursiveKeyUpdateTestRepo {

	static GroupAccessControlManager acm;
	static ContentName directoryBase, userKeyStorePrefix, userNamespace, groupStore;

	static final int numberOfusers = 2;
	static TestUserData td;
	static String[] friendlyNames;

	static final int numberOfGroups = 5;
	static String[] groupName = new String[numberOfGroups];
	static Group[] group = new Group[numberOfGroups];
	static CCNTime[] groupKeyCreationTime = new CCNTime[numberOfGroups];

	static CCNHandle handle;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		Log.setLevel(java.util.logging.Level.INFO);
		directoryBase = ContentName.fromNative("/test/GroupRecursiveKeyUpdateTestRepo");
		groupStore = GroupAccessControlProfile.groupNamespaceName(directoryBase);
		userKeyStorePrefix = ContentName.fromNative(directoryBase, "_access_");
		userNamespace = ContentName.fromNative(directoryBase, "home");

		// create user identities with TestUserData		
		td = new TestUserData(userKeyStorePrefix, numberOfusers, true, "password".toCharArray(), CCNHandle.open());
		td.publishUserKeysToRepository(userNamespace);
		friendlyNames = td.friendlyNames().toArray(new String[0]);				
		
		// create ACM
		handle = td.getHandleForUser(friendlyNames[1]);
		acm = new GroupAccessControlManager(directoryBase, groupStore, userNamespace, handle);
		acm.publishMyIdentity(ContentName.fromNative(userNamespace, friendlyNames[1]), handle.keyManager().getDefaultPublicKey());
	}
	
	/**
	 * Ensures that the tests run in the correct order.
	 * @throws Exception
	 */
	@Test
	public void testInOrder() throws Exception {
		createGroups();
		testRecursiveGroupAncestors();
		removeMemberFromGroup0();
	}
	

	/**
	 * We create the following group structure:
	 * 
	 *              Group3
	 *              /   \
	 *          Group1  Group2
	 *             \     /  \
	 *             Group0  Group4
	 *             /   \   /
	 *          User0  User1 
	 * 
	 * @throws Exception
	 */
	public void createGroups() throws Exception {
		Random rand = new Random();

		// create group0 containing user0 and user1
		ArrayList<Link> G0Members = new ArrayList<Link>();
		G0Members.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[0])));
		G0Members.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		groupName[0] = "group0-" + rand.nextInt(10000);
		group[0] = acm.groupManager().createGroup(groupName[0], G0Members);
		
		// create group4 containing user1
		ArrayList<Link> G4Members = new ArrayList<Link>();
		G4Members.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[1])));
		groupName[4] = "group4-" + rand.nextInt(10000);
		group[4] = acm.groupManager().createGroup(groupName[4], G4Members);
		
		// create group1 and group2 containing group0
		ArrayList<Link> G1G2Members = new ArrayList<Link>();
		G1G2Members.add(new Link(ContentName.fromNative(groupStore, groupName[0])));
		groupName[1] = "group1-" + rand.nextInt(10000);
		group[1] = acm.groupManager().createGroup(groupName[1], G1G2Members);
		groupName[2] = "group2-" + rand.nextInt(10000);
		group[2] = acm.groupManager().createGroup(groupName[2], G1G2Members);
		
		// create group3 containing group1 and group2
		ArrayList<Link> G3Members = new ArrayList<Link>();
		G3Members.add(new Link(ContentName.fromNative(groupStore, groupName[1])));
		G3Members.add(new Link(ContentName.fromNative(groupStore, groupName[2])));
		groupName[3] = "group3-" + rand.nextInt(10000);
		group[3] = acm.groupManager().createGroup(groupName[3], G3Members);
		
		// record the creation time of the original group keys
		for (int i=0; i<numberOfGroups; i++) groupKeyCreationTime[i] = group[i].publicKeyVersion();

		// check the size of the groups
		Assert.assertEquals(2, group[0].membershipList().membershipList().size());
		Assert.assertEquals(1, group[1].membershipList().membershipList().size());
		Assert.assertEquals(1, group[2].membershipList().membershipList().size());
		Assert.assertEquals(2, group[3].membershipList().membershipList().size());
		Assert.assertEquals(1, group[4].membershipList().membershipList().size());
	}

	public void testRecursiveGroupAncestors() throws Exception {
		ArrayList<Link> ancestors = group[0].recursiveAncestorList(null);
		// Group0 should have 3 ancestors, not 4 (check that Group3 is not double-counted)
		Assert.assertEquals(3, ancestors.size());
	}
	
	/**
	 * We delete User0 from Group0 to cause a recursive key update for groups 0, 1, 2 and 3 (but not Group4).
	 * 
	 *              Group3
	 *              /   \
	 *          Group1  Group2
	 *             \     /  \
	 *             Group0   Group4
	 *             /   \   /
	 *            ---  User1 
	 * 
	 * @throws Exception
	 */
	public void removeMemberFromGroup0() throws Exception {
		// delete user0 from group0
		ArrayList<Link> membersToRemove = new ArrayList<Link>();
		membersToRemove.add(new Link(ContentName.fromNative(userNamespace, friendlyNames[0])));
		group[0].removeMembers(membersToRemove);
		Thread.sleep(1000);
		
		// check group0 is of size 1 
		Assert.assertEquals(1, group[0].membershipList().membershipList().size());
		
		// check keys of group0, group1, group2 and group3 were updated
		Assert.assertTrue(group[0].publicKeyVersion().after(groupKeyCreationTime[0]));
		Assert.assertTrue(group[1].publicKeyVersion().after(groupKeyCreationTime[1]));
		Assert.assertTrue(group[2].publicKeyVersion().after(groupKeyCreationTime[2]));
		Assert.assertTrue(group[3].publicKeyVersion().after(groupKeyCreationTime[3]));
		
		// check key of group4 was not updated
		Assert.assertTrue(group[4].publicKeyVersion().equals(groupKeyCreationTime[4]));
	}
	
}
