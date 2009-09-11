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

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.DataUtils;


/**
 * This represents a Component with an Exclude filter
 */
public class ExcludeComponent extends Exclude.Element implements Comparable<ExcludeComponent> {
	public static final String COMPONENT_ELEMENT = "Component";
	protected byte [] body = null;
	
	public ExcludeComponent(byte [] component) {
		body = component.clone();
	}

	public ExcludeComponent() {
	}
	
	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		body = decoder.readBinaryElement(getElementLabel());
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		encoder.writeElement(getElementLabel(), body);
	}
	
	public int compareTo(ExcludeComponent component) {
		return DataUtils.compare(body, component.body);
	}

	public int compareTo(byte [] component) {
		return DataUtils.compare(body, component);
	}

	@Override
	public String getElementLabel() { return COMPONENT_ELEMENT; }

	@Override
	public boolean validate() {
		return body != null;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == null)
			return false;
		if (!(obj instanceof ExcludeComponent))
			return false;
		ExcludeComponent ec = (ExcludeComponent) obj;
		return DataUtils.arrayEquals(body, ec.body);
	}

	public byte [] getBytes() {
		return body.clone();
	}
}
