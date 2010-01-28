/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2010 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.support;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ccnx.ccn.config.SystemConfiguration;

/**
 * Wrapper for the standard java.util Logging classes.
 * 
 * This allows log messages which will not actually be output due to being at a lower
 * level than the current logging level to not affect performance by performing expensive calculations to
 * compute their parameters.
 * 
 * To send log entries to file, specify the log output directory using either the system property
 * org.ccnx.ccn.LogDir or the environment variable CCN_LOG_DIR.  To override the default 
 * log level for whatever program you are running, set the system property org.ccnx.ccn.LogLevel.
 */
public class Log {

	/**
	 * Allow override on command line or from configuration file.
	 */
	public static final String DEFAULT_APPLICATION_CLASS =
		"org.ccnx.ccn.CCNHandle";

	public static final String DEFAULT_LOG_FILE = "ccn_";
	public static final String DEFAULT_LOG_SUFFIX = ".log";
	public static final Level DEFAULT_LOG_LEVEL = Level.INFO;
	
	/**
	 * Properties and environment variables to set log parameters.
	 */
	public static final String DEFAULT_LOG_LEVEL_PROPERTY = "org.ccnx.ccn.LogLevel";
	public static final String LOG_DIR_PROPERTY = "org.ccnx.ccn.LogDir";
	public static final String LOG_DIR_ENV = "CCN_LOG_DIR";
	
	static Logger _systemLogger = null;
	static int _level;
	static boolean useDefaultLevel = true; // reset if an external override of the default level was specified
	
	static {
		// Can add an append=true argument to generate appending behavior.
		Handler theHandler = null;
		_systemLogger = Logger.getLogger(DEFAULT_APPLICATION_CLASS);

		
		String logdir = System.getProperty(LOG_DIR_PROPERTY);
		if (null == logdir) {
			logdir = System.getenv(LOG_DIR_ENV);
		}

		// Only set up file handler if log directory is set
		if (null != logdir) {
			StringBuffer logFileName = new StringBuffer();
			try {
				// See if log dir exists, if not make it.
				File dir = new File(logdir);
				if (!dir.exists() || !dir.isDirectory()) {
					if (!dir.mkdir()) {
						System.err.println("Cannot open log directory "
								+ logdir);
						throw new IOException("Cannot open log directory "
								+ logdir);
					}
				}
				String sep = System.getProperty("file.separator");

				logFileName.append(logdir + sep + DEFAULT_LOG_FILE);
				Date theDate = new Date();
				SimpleDateFormat df = new SimpleDateFormat("yy-MM-dd-HHmmss");
				logFileName.append(df.format(theDate));
				String pid = SystemConfiguration.getPID();
				if (null != pid) {
					logFileName.append("-" + pid);
				} else {
					logFileName.append("-R" + new Random().nextInt(1000));
				}
				logFileName.append(DEFAULT_LOG_SUFFIX);

				theHandler = new FileHandler(logFileName.toString());
				// Force a standard XML encoding (avoids unusual ones like MacRoman in XML)
				theHandler.setEncoding("UTF-8");
				System.out.println("Writing log records to " + logFileName);
				
			} catch (IOException e) {
				// Can't open that file
				System.err.println("Cannot open log file: " + logFileName);
				e.printStackTrace();

				theHandler = new ConsoleHandler();
			}
		}
		if (null != theHandler) {
			_systemLogger.addHandler(theHandler);
		}
		// Could just do a console handler if the file won't open.
		// Do that eventually, for debugging put in both.
		// Actually, right now, seem to get a console handler by default.
		// This move is anti-social, but a starting point.  We don't want 
		// any handlers to be more restrictive then the level set for 
		// our _systemLevel
		Handler[] handlers = Logger.getLogger( "" ).getHandlers();
		for ( int index = 0; index < handlers.length; index++ ) {
			handlers[index].setLevel( Level.ALL );
			
			// TODO Enabling the following by default seems to cause ccn_repo to 
			// hang when run from the command line, at least on Leopard.
			// Not sure why, so make it a special option.
			if (SystemConfiguration.hasLoggingConfigurationProperty(SystemConfiguration.DETAILED_LOGGER)) {
				if (handlers[index] instanceof ConsoleHandler) {
					handlers[index].setFormatter(new DetailedFormatter());
				}
			}
		}
		
		// Allow override of default log level.
		String logLevelName = System.getProperty(DEFAULT_LOG_LEVEL_PROPERTY);
		
		Level logLevel = DEFAULT_LOG_LEVEL;
		
		if (null != logLevelName) {
			try {
				logLevel = Level.parse(logLevelName);
				useDefaultLevel = false;
			} catch (IllegalArgumentException e) {
				logLevel = DEFAULT_LOG_LEVEL;
			}
		}

		// We also have to set our logger to log finer-grained
		// messages
		setLevel(logLevel);
	}

	public static String getApplicationClass() {
		return DEFAULT_APPLICATION_CLASS;
	}
	
	public static void exitApplication() {
		// Clean up and get out, we've had an unrecovereable error.
		_systemLogger.severe("Exiting application.");
		System.exit(-1);
	}
	
	public static void abort() {
		_systemLogger.warning("Unrecoverable error. Exiting data collection.");
		exitApplication(); // save partial results?
	}
	
	// These following methods duplicate methods provided by java.util.Logger
	// but add varargs functionality which allows args to only have .toString()
	// called when logging is enabled.
	/**
	 * Logs message with level = info
	 * @see Log#log(Level, String, Object...)
	 */
	public static void info(String msg, Object... params) {
		doLog(Level.INFO, msg, params);
	}

	/**
	 * Logs message with level = warning
	 * @see Log#log(Level, String, Object...)
	 */
	public static void warning(String msg, Object... params) {
		doLog(Level.WARNING, msg, params);
	}

	/**
	 * Logs message with level = severe
	 * @see Log#log(Level, String, Object...)
	 */
	public static void severe(String msg, Object... params) {
		doLog(Level.SEVERE, msg, params);
	}

	/**
	 * Logs message with level = fine
	 * @see Log#log(Level, String, Object...)
	 */
	public static void fine(String msg, Object... params) {
		doLog(Level.FINE, msg, params);
	}

	/**
	 * Logs message with level = finer
	 * @see Log#log(Level, String, Object...)
	 */
	public static void finer(String msg, Object... params) {
		doLog(Level.FINER, msg, params);
	}

	/**
	 * Logs message with level = finest
	 * @see Log#log(Level, String, Object...)
	 */
	public static void finest(String msg, Object... params) {
		doLog(Level.FINEST, msg, params);
	}

	// pass these methods on to the java.util.Logger for convenience
	public static void setLevel(Level l) {
		_systemLogger.setLevel(l);
		_level = l.intValue();
	}

	/**
	 * Set the default log level that will be in effect unless overridden by
	 * the system property.  Use of this method allows a program to change the 
	 * default logging level while still allowing external override by the user
	 * at runtime.
	 * @param l the new default level
	 */
	public static void setDefaultLevel(Level l) {
		if (useDefaultLevel) {
			setLevel(l);
		} // else we're not using the default level and should not change what is set
	}
	
	/**
	 * Gets the current log level
	 * @return
	 */
	public static Level getLevel() {
		return _systemLogger.getLevel();
	}

	/**
	 * The main logging wrapper. Allows for variable parameters to the message.
	 * Using the variable parameters here rather then constructing the message
	 * yourself helps reduce CPU load when logging is disabled. (Since the
	 * params do not have their .toString() methods called if the message is not
	 * logged).
	 * @param l Log level.
	 * @param msg Message or format string.
	 * @see java.text.MessageFormat
	 * @param params
	 */
	public static void log(Level l, String msg, Object... params) {
		// we must call doLog() to ensure caller is in right place on stack
		doLog(l, msg, params);
	}

	@SuppressWarnings("unchecked")
	protected static void doLog(Level l, String msg, Object... params) {
		if (l.intValue() < _level)
			return;
		StackTraceElement ste = Thread.currentThread().getStackTrace()[3];
		Class c;
		try {
			c = Class.forName(ste.getClassName());
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
		for(Object o : params) {
			if (o == null) {
				o = "(null)";
			}
		}
		_systemLogger.logp(l, c.getCanonicalName(), ste.getMethodName(), msg, params);
	}
	
	public static void flush() {
		Handler [] handlers = _systemLogger.getHandlers();
		for (int i=0; i < handlers.length; ++i) {
			handlers[i].flush();
		}
	}
	
	public static void warningStackTrace(Throwable t) {
		logStackTrace(Level.WARNING, t);
	}

	public static void infoStackTrace(Throwable t) {
		logStackTrace(Level.INFO, t);
	}
	
	public static void logStackTrace(Level level, Throwable t) {
		 StringWriter sw = new StringWriter();
	     t.printStackTrace(new PrintWriter(sw));
	     _systemLogger.log(level, sw.toString());
	}
	
	public static void logException(String message, 
			Exception e) {
		_systemLogger.warning(message);
		Log.warningStackTrace(e);
	}
}
