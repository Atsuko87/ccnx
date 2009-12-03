/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.ccnd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLCodecFactory;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public class PrefixRegistrationManager extends CCNDaemonHandle {

	public enum ActionType {
		Register ("prefixreg"), SelfRegister("selfreg"), UnRegister("unreg");
		ActionType(String st) { this.st = st; }
		private final String st;
		public String value() { return st; }
	}
	
	public static final Integer CCN_FORW_ACTIVE = 1;
	public static final Integer CCN_FORW_CHILD_INHERIT = 2;
	public static final Integer CCN_FORW_ADVERTISE = 4;

		
	public class ForwardingEntry extends GenericXMLEncodable implements XMLEncodable {
		/* extends CCNEncodableObject<PolicyXML>  */
		
		/**
		 * From the XML definitions:
		 * <xs:element name="ForwardingEntry" type="ForwardingEntryType"/>
		 * <xs:complexType name="ForwardingEntryType">
  		 *		<xs:sequence>
      	 *		<xs:element name="Action" type="xs:string" minOccurs="0" maxOccurs="1"/>
      	 * 		<xs:element name="Name" type="NameType" minOccurs="0" maxOccurs="1"/>
      	 * 		<xs:element name="PublisherPublicKeyDigest" type="DigestType" minOccurs="0" maxOccurs="1"/>
         * 		<xs:element name="FaceID" type="xs:nonNegativeInteger" minOccurs="0" maxOccurs="1"/>
       	 * 	 	<xs:element name="ForwardingFlags" type="xs:nonNegativeInteger" minOccurs="0" maxOccurs="1"/>
       	 * 		<xs:element name="FreshnessSeconds" type="xs:nonNegativeInteger" minOccurs="0" maxOccurs="1"/>
      	 * 		</xs:sequence>
      	 * 	</xs:complexType>
		 */

		protected static final String 	FORWARDING_ENTRY_OBJECT_ELEMENT = "ForwardingEntry";
		protected static final String		ACTION_ELEMENT = "Action";
		protected static final String		FACE_ID_ELEMENT = "FaceID";
		protected static final String		FORWARDING_FLAGS_ELEMENT = "ForwardingFlags";
		protected static final String		FRESHNESS_ELEMENT = "FreshnessSeconds";


		protected String		_action;
		protected ContentName	_prefixName;
		protected PublisherPublicKeyDigest _ccndId;
		protected Integer		_faceID;
		protected Integer		_flags;
		protected Integer 		_lifetime;


		public ForwardingEntry(ContentName prefixName, Integer faceID, Integer flags) {
			_action = ActionType.Register.value();
			_prefixName = prefixName;
			_faceID = faceID;
			_flags = flags;
		}

		public ForwardingEntry(ActionType action, ContentName prefixName, PublisherPublicKeyDigest ccndId, 
								Integer faceID, Integer flags, Integer lifetime) {
			_action = action.value();
			_ccndId = ccndId;
			_prefixName = prefixName;
			_faceID = faceID;
			_flags = flags;
			_lifetime = lifetime;
		}

		public ForwardingEntry(byte[] raw) {
			ByteArrayInputStream bais = new ByteArrayInputStream(raw);
			XMLDecoder decoder = XMLCodecFactory.getDecoder(BinaryXMLCodec.CODEC_NAME);
			try {
				decoder.beginDecoding(bais);
				decode(decoder);
				decoder.endDecoding();	
			} catch (ContentDecodingException e) {
				String reason = e.getMessage();
				Log.fine("Unexpected error decoding ForwardingEntry from bytes.  reason: " + reason + "\n");
				Log.warningStackTrace(e);
				throw new IllegalArgumentException("Unexpected error decoding ForwardingEntry from bytes.  reason: " + reason);
			}
		}
		
		public ForwardingEntry() {
		}

		public Integer faceID() { return _faceID; }
		public void setFaceID(Integer faceID) { _faceID = faceID; }

		public String action() { return _action; }
		
		public PublisherPublicKeyDigest ccndId() { return _ccndId; }
		public void setccndId(PublisherPublicKeyDigest id) { _ccndId = id; }

		public String toFormattedString() {
			String out = "";
			if (null != _action) {
				out.concat("Action: "+ _action + "\n");
			} else {
				out.concat("Action: not present\n");
			}
			if (null != _faceID) {
				out.concat("FaceID: "+ _faceID.toString() + "\n");
			} else {
				out.concat("FaceID: not present\n");
			}
			if (null != _prefixName) {
				out.concat("Prefix Name: "+ _prefixName + "\n");
			} else {
				out.concat("Prefix Name: not present\n");
			}
			if (null != _flags) {
				out.concat("Flags: "+ _flags.toString() + "\n");
			} else {
				out.concat("Flags: not present\n");
			}
			return out;
		}	

		public byte[] getBinaryEncoding() {
			// Do setup. Binary codec doesn't write a preamble or anything.
			// If allow to pick, text encoder would sometimes write random stuff...
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			XMLEncoder encoder = XMLCodecFactory.getEncoder(BinaryXMLCodec.CODEC_NAME);
			try {
				encoder.beginEncoding(baos);
				encode(encoder);
				encoder.endEncoding();	
			} catch (ContentEncodingException e) {
				String reason = e.getMessage();
				Log.fine("Unexpected error encoding allocated ForwardingEntry.  reason: " + reason + "\n");
				Log.warningStackTrace(e);
				throw new IllegalArgumentException("Unexpected error encoding allocated ForwardingEntry.  reason: " + reason);
			}
			return baos.toByteArray();
		}

		public boolean validateAction(String action) {
			if (action != null){
				if (action.equals(ActionType.Register.value())) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Used by NetworkObject to decode the object from a network stream.
		 * @see org.ccnx.ccn.impl.encoding.XMLEncodable
		 */
		public void decode(XMLDecoder decoder) throws ContentDecodingException {
			decoder.readStartElement(getElementLabel());
			if (decoder.peekStartElement(ACTION_ELEMENT)) {
				_action = decoder.readUTF8Element(ACTION_ELEMENT); 
			}
			if (decoder.peekStartElement(ContentName.CONTENT_NAME_ELEMENT)) {
				_prefixName = new ContentName();
				_prefixName.decode(decoder) ;
			}
			if (decoder.peekStartElement(PublisherPublicKeyDigest.PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT)) {
				_ccndId = new PublisherPublicKeyDigest();
				_ccndId.decode(decoder);
			}
			if (decoder.peekStartElement(FACE_ID_ELEMENT)) {
				_faceID = decoder.readIntegerElement(FACE_ID_ELEMENT); 
			}
			if (decoder.peekStartElement(FORWARDING_FLAGS_ELEMENT)) {
				_flags = decoder.readIntegerElement(FORWARDING_FLAGS_ELEMENT); 
			}
			if (decoder.peekStartElement(FRESHNESS_ELEMENT)) {
				_lifetime = decoder.readIntegerElement(FRESHNESS_ELEMENT); 
			}
			decoder.readEndElement();
		}

		/**
		 * Used by NetworkObject to encode the object to a network stream.
		 * @see org.ccnx.ccn.impl.encoding.XMLEncodable
		 */
		public void encode(XMLEncoder encoder) throws ContentEncodingException {
			if (!validate()) {
				throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
			}
			encoder.writeStartElement(getElementLabel());
			if (null != _action && _action.length() != 0)
				encoder.writeElement(ACTION_ELEMENT, _action);	
			if (null != _prefixName) {
				_prefixName.encode(encoder);
			}
			if (null != _ccndId) {
				_ccndId.encode(encoder);
			}
			if (null != _faceID) {
				encoder.writeIntegerElement(FACE_ID_ELEMENT, _faceID);
			}
			if (null != _flags) {
				encoder.writeIntegerElement(FORWARDING_FLAGS_ELEMENT, _flags);
			}
			if (null != _lifetime) {
				encoder.writeIntegerElement(FRESHNESS_ELEMENT, _lifetime);
			}
			encoder.writeEndElement();   			
		}

		@Override
		public String getElementLabel() { return FORWARDING_ENTRY_OBJECT_ELEMENT; }

		@Override
		public boolean validate() {
			if (validateAction(_action)){
				return true;
			}
			return false;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((_action == null) ? 0 : _action.hashCode());
			result = prime * result + ((_prefixName == null) ? 0 : _prefixName.hashCode());
			result = prime * result + ((_ccndId == null) ? 0 : _ccndId.hashCode());
			result = prime * result + ((_faceID == null) ? 0 : _faceID.hashCode());
			result = prime * result + ((_flags == null) ? 0 : _flags.hashCode());
			result = prime * result + ((_lifetime == null) ? 0 : _lifetime.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			ForwardingEntry other = (ForwardingEntry) obj;
			if (_action == null) {
				if (other._action != null) return false;
			} else if (!_action.equals(other._action)) return false;
			if (_prefixName == null) {
				if (other._prefixName != null) return false;
			} else if (!_prefixName.equals(other._prefixName)) return false;
			if (_ccndId == null) {
				if (other._ccndId != null) return false;
			} else if (!_ccndId.equals(other._ccndId)) return false;
			if (_faceID == null) {
				if (other._faceID != null) return false;
			} else if (!_faceID.equals(other._faceID)) return false;
			if (_flags == null) {
				if (_flags != null) return false;
			} else if (!_flags.equals(other._flags)) return false;
			if (_lifetime == null) {
				if (other._lifetime != null) return false;
			} else if (!_lifetime.equals(other._lifetime)) return false;
			return true;
		}

	} /* ForwardingEntry */

	/*************************************************************************************/
	/*************************************************************************************/

	public PrefixRegistrationManager(CCNHandle handle) throws CCNDaemonException {
		super(handle, null);
	}

	public PrefixRegistrationManager(CCNHandle handle, PublisherPublicKeyDigest ccndID) throws CCNDaemonException {
		super(handle, ccndID);
	}
	
	public PrefixRegistrationManager() {
	}

	public void registerPrefix(String uri, Integer faceID, Integer flags) throws CCNDaemonException {
		this.registerPrefix(uri, null, faceID, flags, Integer.MAX_VALUE);
	}
	
	public void registerPrefix(String uri, PublisherPublicKeyDigest publisher, Integer faceID, Integer flags, 
							Integer lifetime) throws CCNDaemonException {
		if (null == publisher)
			publisher = _ccndId;
		
		ContentName prefixToRegister;
		try {
			prefixToRegister = ContentName.fromURI(uri);
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			Log.fine("MalformedContentName (" + uri + ") , reason: " + reason + "\n");
			Log.warningStackTrace(e);
			String msg = ("MalformedContentName (" + uri + ") , reason: " + reason);
			throw new CCNDaemonException(msg);
		}
		
		ForwardingEntry forward = new ForwardingEntry(ActionType.Register, prefixToRegister, publisher, faceID, flags, lifetime);
		byte[] entryBits = forward.getBinaryEncoding();

		/*
		 * First create a name that looks like 'ccnx:/ccnx/CCNDId/action/ContentObjectWithForwardInIt'
		 */
		final String startURI = "ccnx:/ccnx/";
		ContentName interestName = null;
		try {
			interestName = ContentName.fromURI(startURI);
			interestName = ContentName.fromNative(interestName, _ccndId.digest());
			interestName = ContentName.fromNative(interestName, ActionType.Register.value());
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			Log.fine("Call to create ContentName failed: " + reason + "\n");
			Log.warningStackTrace(e);
			String msg = ("Unexpected MalformedContentNameStringException in call creating ContentName for Interest, reason: " + reason);
			throw new CCNDaemonException(msg);
		}
		super.sendIt(interestName, entryBits);
	}

	
	public Integer selfRegisterPrefix(String uri) throws CCNDaemonException {
		return this.selfRegisterPrefix(uri, _ccndId, null, null, Integer.MAX_VALUE);
	}
	public Integer selfRegisterPrefix(String uri, PublisherPublicKeyDigest ccndId, Integer faceID, Integer flags, 
				Integer lifetime) throws CCNDaemonException {
		ContentName prefixToRegister;
		try {
			prefixToRegister = ContentName.fromURI(uri);
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			Log.warningStackTrace(e);
			String msg = ("MalformedContentName for prefix to register (" + uri + ") , reason: " + reason);
			Log.fine(msg);
			throw new CCNDaemonException(msg);
		}
		
		final String startURI = "ccnx:/ccnx/";
		ContentName interestName;
		try {
			interestName = ContentName.fromURI(startURI);
			interestName = ContentName.fromNative(interestName, ccndId.digest());
			interestName = ContentName.fromNative(interestName, ActionType.SelfRegister.value());
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			String msg = ("Unexpected MalformedContentNameStringException in call creating ContentName " + startURI + ", reason: " + reason);
			Log.fine(msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
		ForwardingEntry forward = new ForwardingEntry(ActionType.SelfRegister, prefixToRegister, ccndId, faceID, flags, lifetime);
		byte[] entryBits = forward.getBinaryEncoding();

		byte[] payloadBack = super.sendIt(interestName, entryBits);
		ForwardingEntry entryBack = new ForwardingEntry(payloadBack);

		return entryBack.faceID(); 
	}
	
	
	
}
