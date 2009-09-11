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

package org.ccnx.ccn.test.endtoend;

import java.util.concurrent.Semaphore;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.protocol.ContentObject;
import org.junit.BeforeClass;


// NOTE: This test requires ccnd to be running and complementary source process

public class BaseLibrarySink {
	static CCNHandle library = null;
	Semaphore sema = new Semaphore(0);
	int next = 0;
	protected static Throwable error = null; // for errors in callback
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		library = CCNHandle.open();
		// Set debug level: use for more FINE, FINER, FINEST for debug-level tracing
		// Library.setLevel(Level.FINEST);
	}

	/**
	 * Subclassible object processing operations, to make it possible to easily
	 * implement tests based on this one.
	 * @author smetters
	 *
	 */
	public void checkGetResults(ContentObject getResults) {
		System.out.println("Got result: " + getResults.name());
	}
}
