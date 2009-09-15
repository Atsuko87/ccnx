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

package org.ccnx.ccn.io.content;

import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.PublisherID;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;

/**
 * A specifier for the information that can be used to authenticate the target of a Link.
 */
public class LinkAuthenticator extends GenericXMLEncodable implements XMLEncodable, Comparable<LinkAuthenticator> {

    public static final String LINK_AUTHENTICATOR_ELEMENT = "LinkAuthenticator";
    public static final String NAME_COMPONENT_COUNT_ELEMENT = "NameComponentCount";
    protected static final String TIMESTAMP_ELEMENT = "Timestamp";
    protected static final String CONTENT_TYPE_ELEMENT = "Type";
    protected static final String CONTENT_DIGEST_ELEMENT = "ContentDigest";
   
    protected PublisherID	_publisher = null;
    protected Integer		_nameComponentCount = null;
    protected CCNTime		_timestamp = null;
    protected SignedInfo.ContentType 	_type = null;
    protected byte []		_contentDigest = null; // encoded DigestInfo
    
    public LinkAuthenticator(
    		PublisherID publisher, 
    		Integer nameComponentCount,
    		CCNTime timestamp, 
			SignedInfo.ContentType type, 
       		byte [] contentDigest) {
    	super();
    	this._publisher = publisher;
    	this._nameComponentCount = nameComponentCount;
     	this._type = type;
    	this._contentDigest = contentDigest;
    }
    	    
    public LinkAuthenticator(PublisherID publisher) {
    	super();
    	this._publisher = publisher;
    }

    public LinkAuthenticator() {}
    
    public boolean empty() {
    	return (emptyPublisher() && 
    			emptyNameComponentCount() &&
    			emptyTimestamp() &&
    			emptyContentType() &&
    			emptyContentDigest());
    }
    
    public boolean emptyPublisher() {
    	if ((null != publisherID()) && (null != publisherID().id()) && (0 != publisherID().id().length))
    		return false;
    	return true;
    }
    
    public boolean emptyNameComponentCount() { 
    	return (null == _nameComponentCount);
    }
    
    public boolean emptyContentDigest() {
    	if ((null != contentDigest()) && (0 != contentDigest().length))
    		return false;
    	return true;   	
    }
    
    public boolean emptyContentType() { 
    	return (null == _type);
    }
    
    public boolean emptyTimestamp() {
    	return (null == _timestamp);
    }
    
	public byte[] contentDigest() {
		return _contentDigest;
	}
	
	public void contentDigest(byte[] hash) {
		_contentDigest = hash;
	}
	
	public PublisherPublicKeyDigest publisher() {
		// If PublisherID contained one of these internally, rather than a byte [], it would
		// encode with an unnecessary layer of wrapping and tags.
		return new PublisherPublicKeyDigest(_publisher.id());
	}
	
	public PublisherID.PublisherType publisherType() {
		return _publisher.type();
	}
	
	public PublisherID publisherID() {
		return _publisher;
	}
	
	public void publisher(byte[] publisher, PublisherID.PublisherType publisherType) {
		this._publisher = new PublisherID(publisher, publisherType);
	}
	
	public int nameComponentCount() { return _nameComponentCount; }
	public void nameComponentCount(int nameComponentCount) { _nameComponentCount = new Integer(nameComponentCount); }
	public void clearNameComponentCount() { _nameComponentCount = null; }
	
	public CCNTime timestamp() {
		return _timestamp;
	}
	public void timestamp(CCNTime timestamp) {
      	this._timestamp = timestamp;
	}
	
	public SignedInfo.ContentType type() {
		return _type;
	}
	public void type(SignedInfo.ContentType type) {
		this._type = type;
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(getElementLabel());
		
		if (PublisherID.peek(decoder)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}

		if (decoder.peekStartElement(NAME_COMPONENT_COUNT_ELEMENT)) {
			_nameComponentCount = decoder.readIntegerElement(NAME_COMPONENT_COUNT_ELEMENT);
		}

		if (decoder.peekStartElement(TIMESTAMP_ELEMENT)) {
			_timestamp = decoder.readDateTime(TIMESTAMP_ELEMENT);
		}

		if (decoder.peekStartElement(CONTENT_TYPE_ELEMENT)) {
			String strType = decoder.readUTF8Element(CONTENT_TYPE_ELEMENT);
			_type = SignedInfo.nameToType(strType);
			if (null == _type) {
				throw new XMLStreamException("Cannot parse authenticator type: " + strType);
			}
		}
		
		if (decoder.peekStartElement(CONTENT_DIGEST_ELEMENT)) {
			_contentDigest = decoder.readBinaryElement(CONTENT_DIGEST_ELEMENT);
			if (null == _contentDigest) {
				throw new XMLStreamException("Cannot parse content hash.");
			}
		}
				
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		if (!emptyPublisher()) {
			publisherID().encode(encoder);
		}

		if (!emptyNameComponentCount()) {
			encoder.writeIntegerElement(NAME_COMPONENT_COUNT_ELEMENT, nameComponentCount());
		}

		if (!emptyTimestamp()) {
			encoder.writeDateTime(TIMESTAMP_ELEMENT, timestamp());
		}
		
		if (!emptyContentType()) {
			encoder.writeElement(CONTENT_TYPE_ELEMENT, SignedInfo.typeToName(type()));
		}
		
		if (!emptyContentDigest()) {
			encoder.writeElement(CONTENT_DIGEST_ELEMENT, contentDigest());
		}
		encoder.writeEndElement();   		
	}
	
	@Override
	public String getElementLabel() { return LINK_AUTHENTICATOR_ELEMENT; }

	@Override
	public boolean validate() {
		// any of the fields could be null when used 
		// as a partial-match pattern
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(_contentDigest);
		result = prime
				* result
				+ ((_nameComponentCount == null) ? 0 : _nameComponentCount
						.hashCode());
		result = prime * result
				+ ((_publisher == null) ? 0 : _publisher.hashCode());
		result = prime * result
				+ ((_timestamp == null) ? 0 : _timestamp.hashCode());
		result = prime * result + ((_type == null) ? 0 : _type.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		LinkAuthenticator other = (LinkAuthenticator) obj;
		if (!Arrays.equals(_contentDigest, other._contentDigest))
			return false;
		if (_nameComponentCount == null) {
			if (other._nameComponentCount != null)
				return false;
		} else if (!_nameComponentCount.equals(other._nameComponentCount))
			return false;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		if (_timestamp == null) {
			if (other._timestamp != null)
				return false;
		} else if (!_timestamp.equals(other._timestamp))
			return false;
		if (_type == null) {
			if (other._type != null)
				return false;
		} else if (!_type.equals(other._type))
			return false;
		return true;
	}

	public int compareTo(LinkAuthenticator other) {
		int result = 0;
		if (this == other)
			return 0;
		if (other == null)
			return -1;
		result = DataUtils.compare(_contentDigest, other._contentDigest);
		if (0 != result)
			return result;
		if (_nameComponentCount == null) {
			if (other._nameComponentCount != null)
				return -1;
		} else {
			result = _nameComponentCount.compareTo(other._nameComponentCount);
			if (0 != result)
				return result;
		}
		if (_publisher == null) {
			if (other._publisher != null)
				return -1;
		} else {
			result = _publisher.compareTo(other._publisher);
			if (0 != result)
				return result;
		}
		if (_timestamp == null) {
			if (other._timestamp != null)
				return -1;
		} else {
			result = _timestamp.compareTo(other._timestamp);
			if (0 != result)
				return result;
		}
		if (_type == null) {
			if (other._type != null)
				return -1;
		} else {
			result = _type.compareTo(other._type);
			if (0 != result)
				return result;
		}
		return 0;
	}
}
