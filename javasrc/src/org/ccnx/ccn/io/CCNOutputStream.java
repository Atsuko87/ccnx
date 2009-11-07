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

package org.ccnx.ccn.io;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.Arrays;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.CCNSegmenter;
import org.ccnx.ccn.impl.CCNFlowControl.Shape;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.impl.security.crypto.ContentKeys;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;


/**
 * Basic output stream class which generates segmented content under a given
 * name prefix. Segment naming is generated according to the SegmentationProfile;
 * by default names are sequentially numbered. Name prefixes are taken as specified
 * (no versions or other information is added by this class). Segments are 
 * fixed length (see CCNBlockOutputStream for non fixed-length segments).
 */
public class CCNOutputStream extends CCNAbstractOutputStream {

	/**
	 * Amount of data we keep around prior to forced flush, in terms of segmenter
	 * blocks. We write to a limit lower than the maximum, to allow for expansion
	 * due to encryption.
	 * TODO calculate this dynamically based on the bulk signing method and overhead thereof
	 */
	public static final int BLOCK_BUF_COUNT = 128;

	/**
	 * elapsed length written
	 */
	protected long _totalLength = 0; 
	/**
	 * write pointer - offset into the write buffer at which to write
	 */
	protected int _blockOffset = 0; 
	/**
	 * write buffer
	 */
	protected byte [] _buffer = null;
	/**
	 * base name index of the current set of data to output;
	 * incremented according to the segmentation profile.
	 */
	protected long _baseNameIndex; 
	
	/**
	 * // timestamp we use for writing, set to time first segment is written
	 */
	protected CCNTime _timestamp; 
	
	/**
	 * type of content null == DATA (or ENCR if encrypted)
	 */
	protected ContentType _type; 

	protected CCNDigestHelper _dh;

	/**
	 * Constructor for a simple CCN output stream.
	 * @param name name prefix under which to write content segments
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName name, CCNHandle handle) throws IOException {
		this(name, (PublisherPublicKeyDigest)null, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param name name prefix under which to write content segments
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName name,
						   PublisherPublicKeyDigest publisher,
						   CCNHandle handle) throws IOException {
		this(name, null, publisher, null, null, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param name name prefix under which to write content segments
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName name, ContentKeys keys, CCNHandle handle) throws IOException {
		this(name, null, null, null, keys, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param name name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName name, 
			  			   KeyLocator locator, 
			  			   PublisherPublicKeyDigest publisher,
			  			   ContentKeys keys,
			  			   CCNHandle handle) throws IOException {
		this(name, locator, publisher, null, keys, handle);
	}

	/**
	 * Constructor for a simple CCN output stream.
	 * @param name name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param handle if null, new handle created with CCNHandle#open()
	 * @throws IOException if stream setup fails
	 */
	public CCNOutputStream(ContentName name, 
						   KeyLocator locator, 
						   PublisherPublicKeyDigest publisher,
						   ContentType type, 
						   ContentKeys keys,
						   CCNHandle handle) throws IOException {
		this(name, locator, publisher, type, keys, new CCNFlowControl(name, handle));
	}

	/**
	 * Special purpose constructor.
	 */
	protected CCNOutputStream() {}	

	/**
	 * Low-level constructor used by clients that need to specify flow control behavior.
	 * @param name name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param keys keys with which to encrypt content, if null content either unencrypted
	 * 		or keys retrieved according to local policy
	 * @param flowControl flow controller used to buffer output content
	 * @throws IOException if flow controller setup fails
	 */
	public CCNOutputStream(ContentName name, 
							  KeyLocator locator, 
							  PublisherPublicKeyDigest publisher,
							  ContentType type, 
							  ContentKeys keys,
							  CCNFlowControl flowControl) throws IOException {
		this(name, locator, publisher, type, new CCNSegmenter(flowControl, null, keys));
	}

	/**
	 * Low-level constructor used by subclasses that need to specify segmenter behavior.
	 * @param name name prefix under which to write content segments
	 * @param locator key locator to use, if null, default for key is used.
	 * @param publisher key to use to sign the segments, if null, default for user is used.
	 * @param type type to mark content (see ContentType), if null, DATA is used; if
	 * 			content encrypted, ENCR is used.
	 * @param segmenter segmenter used to segment and sign content, should be already initialized
	 * 		with ContentKeys if needed.
	 * @throws IOException if flow controller setup fails
	 */
	protected CCNOutputStream(ContentName name, 
							  KeyLocator locator, 
							  PublisherPublicKeyDigest publisher, 
							  ContentType type,
							  CCNSegmenter segmenter) throws IOException {

		super(locator, publisher, segmenter);

		ContentName nameToOpen = name;
		if (SegmentationProfile.isSegment(nameToOpen)) {
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
			// DKS -- should we offset output index to next one? might have closed
			// previous stream, so likely not
		}

		// Should have name of root of version we want to open. 
		_baseName = nameToOpen;
		_buffer = new byte[BLOCK_BUF_COUNT * segmenter.getBlockSize()];
		_baseNameIndex = SegmentationProfile.baseSegment();
		_type = type; // null = DATA or ENCR

		_dh = new CCNDigestHelper();
		startWrite(); // set up flow controller to write
	}

	@Override
	protected void startWrite() throws IOException {
		_segmenter.getFlowControl().startWrite(_baseName, Shape.STREAM);
	}
	
	/**
	 * Set the segmentation block size to use. Constraints: needs to be
	 * a multiple of the likely encryption block size (which is, conservatively, 32 bytes).
	 * Default is 4096.
	 * @param blockSize in bytes
	 */
	public void setBlockSize(int blockSize) {
		if (blockSize <= 0) {
			throw new IllegalArgumentException("Cannot set negative or zero block size!");
		}
		// We have an existing buffer. That might contain existing data. Changing the
		// buffer size here to get the right number of blocks might require a forced flush
		// or all sorts of complicated hijinks. For now, just stick with the same buffer;
		// if we manage to go buffer-free, this won't matter.
		getSegmenter().setBlockSize(blockSize);
	}

	/**
	 * Get segmentation block size.
	 * @return block size in bytes
	 */
	public int getBlockSize() {
		return getSegmenter().getBlockSize();
	}

	@Override
	public void close() throws IOException {
		try {
			_segmenter.getFlowControl().beforeClose();
			closeNetworkData();
			_segmenter.getFlowControl().afterClose();
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InterruptedException e) {
			throw new IOException("Low-level network failure!: " + e.getMessage());
		}
	}

	@Override
	public void flush() throws IOException {
		flush(false); // if there is a partial block, don't flush it
	}

	/**
	 * Internal flush.
	 * @param flushLastBlock Should we flush the last (partial) block, or hold it back
	 *   for it to be filled.
	 * @throws IOException on a variety of types of error.
	 */
	protected void flush(boolean flushLastBlock) throws IOException {
		try {
			flushToNetwork(flushLastBlock);
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		} catch (InterruptedException e) {
			throw new IOException("Low-level network failure!: " + e.getMessage());
		} catch (InvalidAlgorithmParameterException e) {
			throw new IOException("Cannot encrypt content -- bad algorithm parameter!: " + e.getMessage());
		} 
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		try {
			writeToNetwork(b, off, len);
		} catch (InvalidKeyException e) {
			throw new IOException("Cannot sign content -- invalid key!: " + e.getMessage());
		} catch (SignatureException e) {
			throw new IOException("Cannot sign content -- signature failure!: " + e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("Cannot sign content -- unknown algorithm!: " + e.getMessage());
		}
	}

	/**
	 * Actually write bytes to the network.
	 * @param buf as in write(byte[], int, int)
	 * @param offset as in write(byte[], int, int)
	 * @param len as in write(byte[])
	 * @throws IOException on network errors
	 * @throws InvalidKeyException if we cannot encrypt content as specified
	 * @throws SignatureException if we cannot sign content
	 * @throws NoSuchAlgorithmException if encryption requests invalid algorithm
	 */
	protected void writeToNetwork(byte[] buf, long offset, long len) throws IOException, InvalidKeyException, SignatureException, NoSuchAlgorithmException {
		if ((len <= 0) || (null == buf) || (buf.length == 0) || (offset >= buf.length))
			throw new IllegalArgumentException("Invalid argument!");

		long bytesToWrite = len;

		// Here's an advantage of the old, complicated way -- with that, only had to allocate
		// as many blocks as you were going to write. 
		while (bytesToWrite > 0) {

			long thisBufAvail = _buffer.length - _blockOffset;
			long toWriteNow = (thisBufAvail > bytesToWrite) ? bytesToWrite : thisBufAvail;

			System.arraycopy(buf, (int)offset, _buffer, (int)_blockOffset, (int)toWriteNow);
			_dh.update(buf, (int) offset, (int)toWriteNow); // add to running digest of data

			bytesToWrite -= toWriteNow; // amount of data left to write in current call
			_blockOffset += toWriteNow; // write offset into current block buffer
			offset += toWriteNow; // read offset into input buffer
			_totalLength += toWriteNow; // increment here so we can write log entries on partial writes
			Log.finest("write: added " + toWriteNow + " bytes to buffer. blockOffset: " + _blockOffset + "( " + (thisBufAvail - toWriteNow) + " left in block), " + _totalLength + " written.");

			if (_blockOffset >= _buffer.length) {
				// We're out of buffers. Time to flush to the network.
				Log.info("write: about to sync one tree's worth of blocks (" + BLOCK_BUF_COUNT +") to the network.");
				flush(); // will reset _blockIndex and _blockOffset
			}
		}
	}

	/**
	 * Flush partial hanging block if we have one.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected void closeNetworkData() throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException, InterruptedException {
		// flush last partial buffers. Remove previous code to specially handle
		// small data objects (written as single blocks without headers); instead
		// write them as single-fragment files. Subclasses will determine whether or not
		// to write a header.
		Log.info("closeNetworkData: final flush, wrote " + _totalLength + " bytes.");
		flush(true); // true means write out the partial last block, if there is one
	}

	/** 
	 * @param flushLastBlock Do we flush a partially-filled last block in the current set
	 *   of blocks? Not normally, we want to fill blocks. So if a user calls a manual
	 *   flush(), we want to flush all full blocks, but not a last partial -- readers of
	 *   this block-fragmented content (other streams make other sorts of fragments, this one
	 *   is designed for files of same block size) expect all fragments but the last to be
	 *   the same size. The only time we flush a last partial is on close(), when it is the
	 *   last block of the data. This flag, passed in only by internal methods, tells us it's
	 *   time to do that.
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws InterruptedException
	 * @throws IOException
	 * @throws InvalidAlgorithmParameterException 
	 */
	protected void flushToNetwork(boolean flushLastBlock) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, InterruptedException, IOException, InvalidAlgorithmParameterException {		

		/**
		 * XXX - Can the blockbuffers have holes?
		 *     DKS: no. The blockCount argument to putMerkleTree is intended to tell
		 *     it how many of the blockBuffers array it should touch (are non-null).
		 *     If there are holes, there is a bigger problem.
		 */

		/**
		 * Partial last block handling. If we are in the middle of writing a file, we only
		 * flush complete blocks; up to _blockOffset % getBlockSize().
		 */
		if (0 == _blockOffset) {
			// nothing to write
			return;
		} else if ((_blockOffset <= getBlockSize()) && (!flushLastBlock)) {
			// Only a single block written. We don't put out partial blocks until
			// close is called (or otherwise have flushLastBlock=true), so it's
			// easy to understand holding in that case. However, if we want to 
			// set finalBlockID, we can't do that till we know it -- till close is
			// called. So we should hold off on writing the last full block as well,
			// until we are closed. Unfortunately that means if you call flush()
			// right before close(), you'll tend to sign all but the last block,
			// and sign that last one separately when you actually flush it.
			return;
		}

		if (null == _timestamp)
			_timestamp = CCNTime.now();

		// First, are we flushing dangling blocks (e.g. on close())? If not, we always
		// keep at least a partial block behind. There are two reasons for this; first to
		// ensure we always write full blocks until the end, and second, to allow us to
		// mark the last block as such. So adjust the number of blocks to write
		// accordingly. 
		boolean preservePartial = false;
		int saveBytes = 0;
		
		// Now, we have a partially or completely full buffer. Do we have a partial last block we want to preserve?
		// If we're not flushing, we want to save a final block (whole or partial) and move
		// it down.
		if (!flushLastBlock) {
			saveBytes = _blockOffset % getBlockSize();
			if (0 == saveBytes) {
				saveBytes = getBlockSize(); // full last block, save it anyway so can mark as last.
			}
			preservePartial = true;
		} // otherwise saveBytes = 0, so ok
		
		// Three cases -- 
		// 1) we have nothing to flush (0 bytes or < a single block) (handled above)
		// 2) we're flushing a single block and can put it out with a straight signature
		// 3) we're flushing more than one block, and need to use a bulk signer.
		// The reading/verification code will
		// cope just fine with a single file written in a mix of bulk and straight signature
		// verified blocks.
		if ((_blockOffset - saveBytes) <= getBlockSize()) {
			// Single block to write. If we get here, we are forcing a flush (see above
			// discussion about holding back partial or even a single full block till
			// forced flush/close in order to set finalBlockID).

			Log.info("flush: asked to put a single block to the network, are we finishing the file? " + flushLastBlock + ".");

			// DKS TODO -- think about types, freshness, fix markers for impending last block/first block
			if ((_blockOffset - saveBytes) < getBlockSize()) {
				Log.warning("flush(): writing hanging partial last block of file: " + (_blockOffset-saveBytes) + " bytes, block total is " + getBlockSize() + ", holding back " + saveBytes + " bytes, called by close? " + flushLastBlock);
			} else {
				Log.warning("flush(): writing single full block of file: " + _baseName + ", holding back " + saveBytes + " bytes.");
			}
			_baseNameIndex = 
				_segmenter.putFragment(_baseName, _baseNameIndex, 
					_buffer, 0, (_blockOffset-saveBytes), 
					_type, _timestamp, null, (flushLastBlock ? _baseNameIndex : null), 
					_locator, _publisher);
		} else {
			Log.info("flush: putting merkle tree to the network, baseName " + _baseName +
					" basenameindex " + ContentName.componentPrintURI(SegmentationProfile.getSegmentNumberNameComponent(_baseNameIndex)) + "; " 
					+ _blockOffset + 
					" bytes written, holding back " + saveBytes + " flushing final blocks? " + flushLastBlock + ".");
			// Generate Merkle tree (or other auth structure) and signedInfos and put contents.
			// We always flush all the blocks starting from 0, so the baseBlockIndex is always 0.
			// DKS TODO fix last block marking
			_baseNameIndex = 
				_segmenter.fragmentedPut(_baseName, _baseNameIndex, _buffer, 0, _blockOffset-saveBytes, getBlockSize(),
									     _type, _timestamp, null, 
									     (flushLastBlock ? CCNSegmenter.LAST_SEGMENT : null), 
									     _locator, _publisher);
		}

		if (preservePartial) {
			System.arraycopy(_buffer, _blockOffset-saveBytes, _buffer, 0, saveBytes);
			_blockOffset = saveBytes;
		} else {
			_blockOffset = 0;
		}
		// zeroise unused bytes
		Arrays.fill(_buffer, _blockOffset, _buffer.length, (byte)0);
	}
	
	/**
	 * @return number of bytes that have been written on this stream.
	 */
	protected long lengthWritten() { 
		return _totalLength;
	}
}
