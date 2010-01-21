/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.profiles.namespace;

import java.io.IOException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.KeyValueSet;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * Used to mark the top level in a namespace under access control
 * This class currently holds no data - it will be extended to hold access control 
 * configuration information for that namespace.
 */
public class Root extends GenericXMLEncodable {
	
	public static final String ROOT_ELEMENT = "Root";
	public static final String PROFILE_NAME_ELEMENT = "ProfileName";
	public static final String PARAMETER_ELEMENT = "Parameters";

	ProfileName _profileName;
	ArrayList<ParameterizedName> _parameterizedNames = new ArrayList<ParameterizedName>();
	KeyValueSet _parameters;
	
	public static class RootObject extends CCNEncodableObject<Root> {

		public RootObject(ContentName name, CCNHandle handle) throws IOException {
			super(Root.class, true, name, handle);
		}

		public RootObject(ContentName name, Root r, SaveType saveType, CCNHandle handle) throws IOException {
			super(Root.class, true, name, r, saveType, handle);
		}

		public RootObject(ContentObject firstBlock, CCNHandle handle)
				throws ContentDecodingException, IOException {
			super(Root.class, true, firstBlock, handle);
		}

		public ContentName namespace() {
			return _baseName.copy(_baseName.count()-2);
		}
		
		public Root root() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
	}

	public static class ProfileName extends ContentName {
		
		public ProfileName(ContentName other) {
			super(other);
		}
		
		public ProfileName() {}
		
		@Override
		public String getElementLabel() {
			return PROFILE_NAME_ELEMENT;
		}
	}

	/**
	 * Set up a part of the namespace to be under access control.
	 * This method writes the root block and root ACL to a repository.
	 * @param name The top of the namespace to be under access control
	 * @param acl The access control list to be used for the root of the
	 * namespace under access control.
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public static void create(ContentName name, ACL acl, SaveType saveType, CCNHandle handle) throws IOException, ConfigurationException {
		Root r = new Root();
		RootObject ro = new RootObject(AccessControlProfile.accessRoot(name), r, saveType, handle);
		ro.save();
		ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(name), acl, handle);
		aclo.save();
	}
	
	public Root(ContentName profileName) {
		_profileName = new ProfileName(profileName);
	}
	
	public Root(ContentName profileName, ArrayList<ParameterizedName> parameterizedNames, KeyValueSet parameters) {
		this(profileName);
		_parameterizedNames.addAll(parameterizedNames);
		_parameters = parameters;
	}

	public Root() {}
	
	public void addParameterizedName(ParameterizedName name) { _parameterizedNames.add(name); }
	
	public ContentName profileName() { return _profileName; }

	public ArrayList<ParameterizedName> parameterizedNames() { return _parameterizedNames; }
	
	public KeyValueSet parameters() { return _parameters; }
	
	public boolean emptyParameters() { return (null == parameters()); }
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		_profileName = new ProfileName();
		_profileName.decode(decoder);

		while (decoder.peekStartElement(ParameterizedName.PARAMETERIZED_NAME_ELEMENT)) {
			ParameterizedName pn = new ParameterizedName();
			pn.decode(decoder);
			_parameterizedNames.add(pn);
		}
		
		if (decoder.peekStartElement(PARAMETER_ELEMENT)) {
			_parameters = new KeyValueSet();
			_parameters.decode(decoder);
		}
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		profileName().encode(encoder);

		// not technically thread-safe, but odds we get used from more than one thread low....
		// make caller lock
		for (ParameterizedName pn : parameterizedNames()) {
			pn.encode(encoder);
		}
		
		if (!emptyParameters()) {
			parameters().encode(encoder);
		}

		encoder.writeEndElement();   		
	}

	@Override
	public boolean validate() {
		return (null != _profileName);
	}

	@Override
	public String getElementLabel() {
		return ROOT_ELEMENT;
	}
}

