package com.parc.ccn.security.crypto.certificates;

import java.math.BigInteger;
import java.util.Enumeration;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERTags;
import org.bouncycastle.asn1.x509.GeneralNames;

/**
 * @author D.K. Smetters
 *
 * Reimplementation of BouncyCastle's AuthorityKeyIdentifier to allow
 * keyID to be set (must take authority key id from subject key id field of
 * issuer's certificate if present; algorithm is not required by the standard so
 * may not match).
  * <pre>
 * id-ce-authorityKeyIdentifier OBJECT IDENTIFIER ::=  { id-ce 35 }
 *
 *   AuthorityKeyIdentifier ::= SEQUENCE {
 *      keyIdentifier             [0] IMPLICIT KeyIdentifier           OPTIONAL,
 *      authorityCertIssuer       [1] IMPLICIT GeneralNames            OPTIONAL,
 *      authorityCertSerialNumber [2] IMPLICIT CertificateSerialNumber OPTIONAL  }
 *
 *   KeyIdentifier ::= OCTET STRING
 * </pre>
*/
public class AuthorityKeyIdentifier implements DEREncodable, DERTags {

	public static final int tag_KeyIdentifier 			= 0;
	public static final int tag_IssuerName 				= 1;
	public static final int tag_issuerSerialNumber	= 2;

	protected ASN1OctetString _keyIdentifier = null;
	protected GeneralNames _issuerName = null;
	protected DERInteger _issuerSerial = null;

	public static AuthorityKeyIdentifier getInstance(ASN1TaggedObject obj,
													 boolean explicit) {
		return getInstance(ASN1Sequence.getInstance(obj, explicit));
   }

	public static AuthorityKeyIdentifier getInstance(Object obj) {
		if (obj instanceof AuthorityKeyIdentifier) 
			return (AuthorityKeyIdentifier)obj;
		else if (obj instanceof ASN1Sequence) {
			return new AuthorityKeyIdentifier((ASN1Sequence)obj);
		}
		
		throw new IllegalArgumentException("Cannot construct an AuthorityKeyIdentifier from a: " +
																	obj.getClass().getName());
	}		

	public AuthorityKeyIdentifier(ASN1Sequence seq) {
		
		Enumeration e = seq.getObjects();	
		DERTaggedObject o = null;
		
		while (e.hasMoreElements()) {
			o = (DERTaggedObject)e.nextElement();
			
			switch (o.getTagNo()) {
				case tag_KeyIdentifier:
					this._keyIdentifier = ASN1OctetString.getInstance(o);
					break;
				case tag_IssuerName:
					this._issuerName = GeneralNames.getInstance(o);
					break;
				case tag_issuerSerialNumber:
					this._issuerSerial = DERInteger.getInstance(o);
					break;
				default:
					throw new IllegalArgumentException("Illegal tag: " + o.getTagNo());
			}
		}
	}

	public AuthorityKeyIdentifier(byte [] keyID, GeneralNames issuerName, BigInteger issuerSerial) {
		
		if (null != keyID)
			this._keyIdentifier = new DEROctetString(keyID);
			
		this._issuerName = issuerName; // clone if not null?
		if (null != issuerSerial)
			this._issuerSerial = new DERInteger(issuerSerial);
	}
	
	/**
	 * Handle common case of a single name
	 **/
	public AuthorityKeyIdentifier(byte [] keyID, ASN1GeneralName name, BigInteger issuerSerial) {
		if (null != keyID)
			this._keyIdentifier = new DEROctetString(keyID);
			
		if (null != name) {
			this._issuerName = new GeneralNames(
					       new DERSequence(name));
		}			
		if (null != issuerSerial)
			this._issuerSerial = new DERInteger(issuerSerial);
	}
	
	public byte [] getKeyIdentifier() {
		if (this._keyIdentifier != null)
			return this._keyIdentifier.getOctets();
		return null;
	}
	
	public void setKeyIdentifier(byte [] keyID) {
		if (null != keyID)
			this._keyIdentifier = new DEROctetString(keyID);
	}
	
	public GeneralNames getIssuerName() {
		return this._issuerName;
	}
	
	public void setIssuerName(GeneralNames name) {
		this._issuerName = name; // clone?
	}
	
	public BigInteger getIssuerSerialNumber() {
		if (null != this._issuerSerial)
			return this._issuerSerial.getValue();
		return null;
	}
	
	public void setIssuerSerialNumber(BigInteger serial) {
		if (null != serial)
			this._issuerSerial = new DERInteger(serial);
	}

     /**
     * <pre>
     *   AuthorityKeyIdentifier ::= SEQUENCE {
     *      keyIdentifier             [0] IMPLICIT KeyIdentifier           OPTIONAL,
     *      authorityCertIssuer       [1] IMPLICIT GeneralNames            OPTIONAL,
     *      authorityCertSerialNumber [2] IMPLICIT CertificateSerialNumber OPTIONAL  }
     *
     *   KeyIdentifier ::= OCTET STRING
     * </pre>
     */
	public DERObject getDERObject() {
		
		ASN1EncodableVector seq = new ASN1EncodableVector();
		
		if (null != this._keyIdentifier)
			seq.add(new DERTaggedObject(false, tag_KeyIdentifier, _keyIdentifier));
			
		if (null != this._issuerName)
			seq.add(new DERTaggedObject(false, tag_IssuerName, _issuerName));
			
		if (null != this._issuerSerial)
			seq.add(new DERTaggedObject(false, tag_issuerSerialNumber, _issuerSerial));

		return new DERSequence(seq);
	}

	public String toString() {
		StringBuffer buf = new StringBuffer("AuthorityKeyIdentifier:");
		buf.append('\n');
		if (null != _keyIdentifier) {
			buf.append("	KeyID:");
			buf.append(new BigInteger(this._keyIdentifier.getOctets()).toString(16));
			buf.append('\n');
		}
		if (null != _issuerName) {
			buf.append("	Issuer:");
			buf.append(_issuerName.toString());
			buf.append('\n');
		}
		if (null != _issuerSerial) {
			buf.append("	Issuer Serial Number:");
			buf.append(this._issuerSerial.getValue().toString(16));
			buf.append('\n');
		}
		return buf.toString();
	}
}
