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

package org.ccnx.ccn.protocol;

import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.util.CryptoUtil;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;


/**
 * Helper wrapper class for publisher IDs. This encodes and decodes
 * as one of 4 inline options, one of which also appears separately
 * as the PublisherPublicKeyDigest.
 * @author smetters
 *
 */
public class PublisherID extends GenericXMLEncodable implements XMLEncodable, Comparable<PublisherID> {

	/**
	 * Move this to a centralized configuration location.
	 */
	public static final String PUBLISHER_ID_DIGEST_ALGORITHM = "SHA-256";
    public static final int PUBLISHER_ID_LEN = 256/8;
    public enum PublisherType {KEY, CERTIFICATE, ISSUER_KEY, ISSUER_CERTIFICATE};

    protected static final HashMap<PublisherType, String> TypeNames = new HashMap<PublisherType, String>();
    protected static final HashMap<String, PublisherType> NameTypes = new HashMap<String, PublisherType>();
    
    public static final String PUBLISHER_CERTIFICATE_DIGEST_ELEMENT = "PublisherCertificateDigest";
    public static final String PUBLISHER_ISSUER_KEY_DIGEST = "PublisherIssuerKeyDigest";
    public static final String PUBLISHER_ISSUER_CERTFICIATE_DIGEST = "PublisherIssuerCertificateDigest";
    
    static {
        TypeNames.put(PublisherType.KEY, PublisherPublicKeyDigest.PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT);
        TypeNames.put(PublisherType.CERTIFICATE, PUBLISHER_CERTIFICATE_DIGEST_ELEMENT);
        TypeNames.put(PublisherType.ISSUER_KEY, PUBLISHER_ISSUER_KEY_DIGEST);
        TypeNames.put(PublisherType.ISSUER_CERTIFICATE, PUBLISHER_ISSUER_CERTFICIATE_DIGEST);
        NameTypes.put(PublisherPublicKeyDigest.PUBLISHER_PUBLIC_KEY_DIGEST_ELEMENT, PublisherType.KEY);
        NameTypes.put(PUBLISHER_CERTIFICATE_DIGEST_ELEMENT, PublisherType.CERTIFICATE);
        NameTypes.put(PUBLISHER_ISSUER_KEY_DIGEST, PublisherType.ISSUER_KEY);
        NameTypes.put(PUBLISHER_ISSUER_CERTFICIATE_DIGEST, PublisherType.ISSUER_CERTIFICATE);
    }

    protected byte [] _publisherID;
    protected PublisherType _publisherType;
    
    /**
     * Create a PublisherID specifying a public key as a signer or issuer
     * @param key the key
     * @param isIssuer false if it signed the content directly, true if it signed the key of the content signer
     */
    public PublisherID(PublicKey key, boolean isIssuer) {
    	_publisherID = generatePublicKeyDigest(key);
    	_publisherType = isIssuer ? PublisherType.ISSUER_KEY : PublisherType.KEY;
    }
    
    /**
     * Create a PublisherID specifying a public key in a certificate as a signer or issuer
     * @param cert the certificate
     * @param isIssuer false if it signed the content directly, true if it signed the key of the content signer
     */
   public PublisherID(X509Certificate cert, boolean isIssuer) throws CertificateEncodingException {
    	_publisherID = generateCertificateDigest(cert);
    	_publisherType = isIssuer ? PublisherType.ISSUER_CERTIFICATE : PublisherType.CERTIFICATE;
    }
	
   /**
    * Create a PublisherID from a raw digest and a type
    * @param publisherID the digest
    * @param publisherType the type
    */
	public PublisherID(byte [] publisherID, PublisherType publisherType) {
		if ((null == publisherID) || (publisherID.length != PUBLISHER_ID_LEN)) {
			throw new IllegalArgumentException("Invalid publisherID!");
		}
		// Alas, Arrays.copyOf doesn't exist in 1.5, and we'd like
		// to be mostly 1.5 compatible for the macs...
		// _publisherPublicKeyDigest = Arrays.copyOf(publisherID, PUBLISHER_ID_LEN);
		_publisherID = new byte[PUBLISHER_ID_LEN];
		System.arraycopy(publisherID, 0, _publisherID, 0, publisherID.length);
		_publisherType = publisherType;
	}
	
	/**
	 * Create a signer PublisherID from an existing PublisherPublicKeyDigest
	 * @param keyID the key digest
	 */
	public PublisherID(PublisherPublicKeyDigest keyID) {
		this(keyID.digest(), PublisherType.KEY);
	}
	
	/**
	 * For use by decoders
	 */
    public PublisherID() {} 
	
    /**
     * Get the id
     * @return id
     */
	public byte [] id() { return _publisherID; }
	
	/**
	 * Get the type
	 * @return type
	 */
	public PublisherType type() { return _publisherType; }
	
	@Override
	public int hashCode() {
		final int PRIME = 31;
		int result = 1;
		result = PRIME * result + Arrays.hashCode(_publisherID);
		result = PRIME * result + ((_publisherType == null) ? 0 : _publisherType.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (PublisherPublicKeyDigest.class == obj.getClass()) {
			if (PublisherType.KEY == this.type())
				return (Arrays.equals(_publisherID, ((PublisherPublicKeyDigest)obj).digest()));
			// TODO DKS fill in...
			throw new UnsupportedOperationException("Have to finish up equals!");			
		}
		if (getClass() != obj.getClass())
			return false;
		final PublisherID other = (PublisherID) obj;
		if (!Arrays.equals(_publisherID, other._publisherID))
			return false;
		if (_publisherType == null) {
			if (other.type() != null)
				return false;
		} else if (!_publisherType.equals(other.type()))
			return false;
		return true;
	}
		
	/**
	 * Type classification methods
	 * @return
	 */
	public boolean isSigner() {
		return ((PublisherType.KEY == type()) || (PublisherType.CERTIFICATE == type()));
	}
	
	/**
	 * Type classification methods
	 * @return
	 */
	public boolean isCertifier() {
		return ((PublisherType.ISSUER_CERTIFICATE == type()) || (PublisherType.ISSUER_KEY == type()));
	}
	
	/**
	 * Type classification methods
	 * @return
	 */
	public static boolean isPublisherType(String name) {
		return NameTypes.containsKey(name);
	}
	
	/**
	 * Name conversion routines for enums. Unnecessary and will be removed.
	 * @param type
	 * @return
	 */
	public static String typeToName(PublisherType type) {
		return TypeNames.get(type);
	}

	/**
	 * Name conversion routines for enums. Unnecessary and will be elided.
	 * @param name
	 * @return
	 */
	public static PublisherType nameToType(String name) {
		return NameTypes.get(name);
	}
	
	/**
	 * This is a choice. Make it possible for users of this class to peek it
	 * when it might be optional, without them having to know about the structure.
	 */
	public static boolean peek(XMLDecoder decoder) throws XMLStreamException {
		String nextTag = decoder.peekStartElement();
		return (null != nameToType(nextTag));
	}

	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		
		// We have a choice here of one of 4 binary element types.
		String nextTag = decoder.peekStartElement();
		
		if (null == nextTag) {
			throw new XMLStreamException("Cannot parse publisher ID.");
		} 
		
		_publisherType = nameToType(nextTag); 
		
		if (null == _publisherType) {
			throw new XMLStreamException("Invalid publisher ID, got unexpected type: " + nextTag);
		}
		_publisherID = decoder.readBinaryElement(nextTag);
		if (null == _publisherID) {
			throw new XMLStreamException("Cannot parse publisher ID of type : " + nextTag + ".");
		}
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		// The format of a publisher ID is a choice, a binary element tagged with
		// one of the 4 publisher types.
		
		encoder.writeElement(getElementLabel(), id());
	}
	
	@Override
	public String getElementLabel() { return typeToName(type()); }

	@Override
	public boolean validate() {
		return ((null != id() && (null != type())));
	}
	
	/**
	 * Helper method to generate a public key digest
	 * @param key the key to digest
	 * @return the digest
	 */
	public static byte [] generatePublicKeyDigest(PublicKey key) {
		return CryptoUtil.generateKeyID(PUBLISHER_ID_DIGEST_ALGORITHM, key);
	}

	/**
	 * Helper method to generate a certificate digest
	 * @param cert the certificate to digest
	 * @return the digest
	 * @throws CertificateEncodingException
	 */
	public static byte [] generateCertificateDigest(X509Certificate cert) throws CertificateEncodingException {
		try {
			return generateCertificateDigest(PUBLISHER_ID_DIGEST_ALGORITHM, cert);
		} catch (NoSuchAlgorithmException e) {
			// DKS --big configuration problem
			Log.warning("Fatal Error: cannot find default algorithm " + PUBLISHER_ID_DIGEST_ALGORITHM);
			throw new RuntimeException("Error: can't find default algorithm " + PUBLISHER_ID_DIGEST_ALGORITHM + "!  " + e.toString());
		}
	}
	
	/**
	 * Helper method to generate a certificate digest
	 * @param digestAlg the digest algorithm to use
	 * @param cert the certificate to digest
	 * @return the digest
	 * @throws CertificateEncodingException
	 * @throws NoSuchAlgorithmException
	 */
    public static byte [] generateCertificateDigest(String digestAlg, X509Certificate cert) 
    							throws CertificateEncodingException, NoSuchAlgorithmException  {
    	
        byte [] id = null;
        try {
            byte [] encoding = cert.getEncoded();
            id = CCNDigestHelper.digest(digestAlg, encoding);
        } catch (CertificateEncodingException e) {
			Log.warning("Cannot encode certificate in PublisherID.generateCertificateID: " + e.getMessage());
			Log.warningStackTrace(e);
			throw e;
		}
        return id;
    }

    /**
     * Implement Comparable
     */
 	public int compareTo(PublisherID o) {
		int result = DataUtils.compare(this.id(), o.id());
		if (0 == result) {
			result = typeToName(this.type()).compareTo(typeToName(o.type()));
		}
		return result;
	}

	@Override
	public String toString() {
		// 	16 would be the most familiar option, but 32 is shorter
		return typeToName(type()) + ":" + CCNDigestHelper.printBytes(id(), 32);
	}
}
