/**
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test.repo;

import java.io.File;
import java.io.IOException;

import org.ccnx.ccn.io.CCNInputStream;
import org.ccnx.ccn.io.RepositoryFileOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.test.LibraryTestBase;
import org.junit.Assert;
import org.junit.BeforeClass;



/**
 * 
 * @author rasmusse
 *
 */

public class RepoTestBase extends LibraryTestBase {
	
	public static final String TOP_DIR = "ccn.test.topdir";
	
	protected static String _topdir;
	protected static String _fileTestDir = "repotest";
	protected static String _repoName = "TestRepository";
	protected static String _globalPrefix = "/parc.com/csl/ccn/repositories";
	protected static File _fileTest;
	protected static ContentName testprefix = ContentName.fromNative(new String[]{"repoTest","pubidtest"});
	protected static ContentName keyprefix = ContentName.fromNative(testprefix,"keys");
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		// Let default logging level be set centrally so it can be overridden by property
		_topdir = System.getProperty(TOP_DIR);
		if (null == _topdir)
			_topdir = "src";
	}
	
	protected void checkNameSpace(String contentName, boolean expected) throws Exception {
		ContentName name = ContentName.fromNative(contentName);
		ContentName baseName = null;
		try {
			baseName = testWriteToRepo(name);
		} catch (IOException ex) {
			if (expected)
				Assert.fail(ex.getMessage());
			return;
		}
		if (!expected)
			Assert.fail("Got a repo response on a bad namespace " + contentName);
		Thread.sleep(1000);

		CCNInputStream input = new CCNInputStream(baseName, getLibrary);
		byte[] buffer = new byte["Testing 1 2 3".length()];
		if (expected) {
			Assert.assertTrue(-1 != input.read(buffer));
			Assert.assertArrayEquals(buffer, "Testing 1 2 3".getBytes());
		} else {
			Assert.assertEquals(-1, input.read(buffer));
		}
		input.close();
	}
	
	protected ContentName testWriteToRepo(ContentName name) throws Exception {
		RepositoryFileOutputStream ros = new RepositoryFileOutputStream(name, putLibrary);	
		byte [] data = "Testing 1 2 3".getBytes();
		ros.write(data, 0, data.length);
		ContentName baseName = ros.getBaseName();
		ros.close();
		return baseName;
	}
}
