package com.parc.ccn.data.query;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.keys.TrustManager;

/**
 * This class represents all the allowed specializations
 * of queries recognized and supported (in a best-effort
 * fashion) at the CCN level.
 * 
 * Implement Comparable to make it much easier to store in
 * a Set and avoid duplicates.
 * @author smetters
 *
 */
public class Interest extends GenericXMLEncodable implements XMLEncodable, Comparable<Interest> {
	
	// Used to remove spurious *'s
	public static final String RECURSIVE_POSTFIX = "*";
	
	public static final String INTEREST_ELEMENT = "Interest";

	protected ContentName _name;
	// DKS TODO can we really support a PublisherID here, or just a PublisherKeyID?
	protected PublisherID _publisher;
	
	/**
	 * TODO: DKS figure out how to handle encoding faster,
	 * and how to handle shorter version of names without
	 * copying, particularly without 1.6 array ops.
	 * @param name
	 * @param authenticator
	 */
	public Interest(ContentName name, 
				   PublisherID publisher) {
		_name = name;
		_publisher = publisher;
	}
	
	public Interest(ContentName name) {
		this(name, null);
	}
	
	public Interest(String name) throws MalformedContentNameStringException {
		this(new ContentName(name), null);
	}

	public Interest() {} // for use by decoders

	public ContentName name() { return _name; }
	
	public PublisherID publisherID() { return _publisher; }
	
	public boolean matches(CompleteName result) {
		if (null == name() || null == result)
			return false; // null name() should not happen, null arg can
		// to get interest that matches everything, should
		// use / (ROOT)
		if (name().isPrefixOf(result.name())) {
			if (null != publisherID()) {
				if ((null == result.authenticator()) ||
					(null == result.authenticator().publisherKeyID())) {
					return false;
				}
				// Should this be more general?
				// TODO DKS handle issuer
				if (TrustManager.getTrustManager().matchesRole(publisherID(), result.authenticator().publisherKeyID())) {
					return true;
				}
			} else {
				return true;
			}
		}
		return false;
	}
	
	public boolean recursive() { return true; }
	
	/**
	 * Thought about encoding and decoding as flat -- no wrapping
	 * declaration. But then couldn't use these solo.
	 */
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(INTEREST_ELEMENT);

		_name = new ContentName();
		_name.decode(decoder);
		
		if (decoder.peekStartElement(PublisherID.PUBLISHER_ID_ELEMENT)) {
			_publisher = new PublisherID();
			_publisher.decode(decoder);
		}
		
		decoder.readEndElement();
	}

	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(INTEREST_ELEMENT);
		
		name().encode(encoder);
		if (null != publisherID())
			publisherID().encode(encoder);

		encoder.writeEndElement();   		
	}
	
	public boolean validate() {
		// DKS -- do we do recursive validation?
		// null authenticator ok
		return (null != name());
	}

	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + ((_publisher == null) ? 0 : _publisher.hashCode());
		result = PRIME * result + ((_name == null) ? 0 : _name.hashCode());
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
		final Interest other = (Interest) obj;
		if (_name == null) {
			if (other._name != null)
				return false;
		} else if (!_name.equals(other._name))
			return false;
		if (_publisher == null) {
			if (other._publisher != null)
				return false;
		} else if (!_publisher.equals(other._publisher))
			return false;
		return true;
	}

	public int compareTo(Interest o) {
		int result = 0;
		if (null != _name) {
			result = _name.compareTo(o.name());
			if (0 != result)
				return result;
		} else {
			if (null != o.name())
				return -1; // sort nothing before something
			// else fall through and compare publishers
		}
		
		if (null != _publisher) {
			result = _publisher.compareTo(o.publisherID());
			return result;
		} else {
			if (null != o.publisherID())
				return -1;
			return 0;
		}
	}
}
