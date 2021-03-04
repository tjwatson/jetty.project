//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.qpack.generator.DuplicateInstruction;
import org.eclipse.jetty.http3.qpack.generator.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.generator.Instruction;
import org.eclipse.jetty.http3.qpack.generator.LiteralNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.generator.SetCapacityInstruction;
import org.eclipse.jetty.http3.qpack.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.table.Entry;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QpackEncoder
{
    private static final Logger LOG = LoggerFactory.getLogger(QpackEncoder.class);
    private static final HttpField[] STATUSES = new HttpField[599];
    public static final EnumSet<HttpHeader> DO_NOT_HUFFMAN =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.PROXY_AUTHENTICATE,
            HttpHeader.PROXY_AUTHORIZATION);
    public static final EnumSet<HttpHeader> DO_NOT_INDEX =
        EnumSet.of(
            // HttpHeader.C_PATH,  // TODO more data needed
            // HttpHeader.DATE,    // TODO more data needed
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.CONTENT_RANGE,
            HttpHeader.ETAG,
            HttpHeader.IF_MODIFIED_SINCE,
            HttpHeader.IF_UNMODIFIED_SINCE,
            HttpHeader.IF_NONE_MATCH,
            HttpHeader.IF_RANGE,
            HttpHeader.IF_MATCH,
            HttpHeader.LOCATION,
            HttpHeader.RANGE,
            HttpHeader.RETRY_AFTER,
            // HttpHeader.EXPIRES,
            HttpHeader.LAST_MODIFIED,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);
    public static final EnumSet<HttpHeader> NEVER_INDEX =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);
    private static final EnumSet<HttpHeader> IGNORED_HEADERS = EnumSet.of(HttpHeader.CONNECTION, HttpHeader.KEEP_ALIVE,
        HttpHeader.PROXY_CONNECTION, HttpHeader.TRANSFER_ENCODING, HttpHeader.UPGRADE);
    private static final PreEncodedHttpField TE_TRAILERS = new PreEncodedHttpField(HttpHeader.TE, "trailers");
    private static final PreEncodedHttpField C_SCHEME_HTTP = new PreEncodedHttpField(HttpHeader.C_SCHEME, "http");
    private static final PreEncodedHttpField C_SCHEME_HTTPS = new PreEncodedHttpField(HttpHeader.C_SCHEME, "https");
    private static final EnumMap<HttpMethod, PreEncodedHttpField> C_METHODS = new EnumMap<>(HttpMethod.class);

    static
    {
        for (HttpStatus.Code code : HttpStatus.Code.values())
        {
            STATUSES[code.getCode()] = new PreEncodedHttpField(HttpHeader.C_STATUS, Integer.toString(code.getCode()));
        }
        for (HttpMethod method : HttpMethod.values())
        {
            C_METHODS.put(method, new PreEncodedHttpField(HttpHeader.C_METHOD, method.asString()));
        }
    }

    public interface Handler
    {
        void onInstruction(Instruction instruction);
    }

    private final ByteBufferPool _bufferPool;
    private final Handler _handler;
    private final QpackContext _context;
    private final int _maxBlockedStreams;
    private boolean _validateEncoding = true;
    private int _knownInsertCount;

    private int _blockedStreams = 0;
    private final Map<Integer, StreamInfo> _streamInfoMap = new HashMap<>();

    public QpackEncoder(Handler handler, int maxBlockedStreams)
    {
        this(handler, maxBlockedStreams, new NullByteBufferPool());
    }

    public QpackEncoder(Handler handler, int maxBlockedStreams, ByteBufferPool bufferPool)
    {
        _handler = handler;
        _bufferPool = bufferPool;
        _context = new QpackContext();
        _maxBlockedStreams = maxBlockedStreams;
        _knownInsertCount = 0;
    }

    public void setCapacity(int capacity)
    {
        _context.getDynamicTable().setCapacity(capacity);
        _handler.onInstruction(new SetCapacityInstruction(capacity));
    }

    public void insertCountIncrement(int increment) throws QpackException
    {
        int insertCount = _context.getDynamicTable().getInsertCount();
        if (_knownInsertCount + increment > insertCount)
            throw new QpackException.StreamException("KnownInsertCount incremented over InsertCount");

        // TODO: release any references to entries which used to insert new entries.
        for (Entry entry : _context.getDynamicTable())
        {
            if (entry.getIndex() > _knownInsertCount)
                break;
        }

        _knownInsertCount += increment;
    }

    public void sectionAcknowledgement(int streamId) throws QpackException
    {
        StreamInfo streamInfo = _streamInfoMap.get(streamId);
        if (streamInfo == null)
            throw new QpackException.StreamException("No StreamInfo for " + streamId);

        // The KnownInsertCount should be updated to the earliest sent RequiredInsertCount on that stream.
        StreamInfo.SectionInfo sectionInfo = streamInfo.acknowledge();
        _knownInsertCount = Math.max(_knownInsertCount, sectionInfo.getRequiredInsertCount());

        // If we have no more outstanding section acknowledgments remove the StreamInfo.
        if (streamInfo.isEmpty())
            _streamInfoMap.remove(streamId);
    }

    public void streamCancellation(int streamId) throws QpackException
    {
        StreamInfo streamInfo = _streamInfoMap.remove(streamId);
        if (streamInfo == null)
            throw new QpackException.StreamException("No StreamInfo for " + streamId);
    }

    public QpackContext getQpackContext()
    {
        return _context;
    }

    public boolean isValidateEncoding()
    {
        return _validateEncoding;
    }

    public void setValidateEncoding(boolean validateEncoding)
    {
        _validateEncoding = validateEncoding;
    }

    public boolean referenceEntry(Entry entry)
    {
        if (entry == null)
            return false;

        if (entry.isStatic())
            return true;

        boolean inEvictionZone = !_context.getDynamicTable().canReference(entry);
        if (inEvictionZone)
            return false;

        // If they have already acknowledged this entry we can reference it straight away.
        if (_knownInsertCount >= entry.getIndex() + 1)
        {
            entry.reference();
            return true;
        }

        return false;
    }

    public boolean referenceEntry(Entry entry, StreamInfo streamInfo)
    {
        if (referenceEntry(entry))
            return true;

        // We may need to risk blocking the stream in order to reference it.
        if (streamInfo.isBlocked())
        {
            streamInfo.getCurrentSectionInfo().block();
            return true;
        }

        if (_blockedStreams < _maxBlockedStreams)
        {
            _blockedStreams++;
            streamInfo.getCurrentSectionInfo().block();
            return true;
        }

        return false;
    }

    public boolean shouldIndex(HttpField httpField)
    {
        return !DO_NOT_INDEX.contains(httpField.getHeader());
    }

    public static boolean shouldHuffmanEncode(HttpField httpField)
    {
        return false; //!DO_NOT_HUFFMAN.contains(httpField.getHeader());
    }

    public ByteBuffer encode(int streamId, HttpFields httpFields) throws QpackException
    {
        // Verify that we can encode without errors.
        if (isValidateEncoding() && httpFields != null)
        {
            for (HttpField field : httpFields)
            {
                String name = field.getName();
                char firstChar = name.charAt(0);
                if (firstChar <= ' ')
                    throw new QpackException.StreamException("Invalid header name: '%s'", name);
            }
        }

        StreamInfo streamInfo = _streamInfoMap.get(streamId);
        if (streamInfo == null)
        {
            streamInfo = new StreamInfo(streamId);
            _streamInfoMap.put(streamId, streamInfo);
        }
        StreamInfo.SectionInfo sectionInfo = new StreamInfo.SectionInfo();
        streamInfo.add(sectionInfo);

        int requiredInsertCount = 0;
        List<EncodableEntry> encodableEntries = new ArrayList<>();
        if (httpFields != null)
        {
            for (HttpField field : httpFields)
            {
                EncodableEntry entry = encode(streamInfo, field);
                encodableEntries.add(entry);

                // Update the required InsertCount.
                int entryRequiredInsertCount = entry.getRequiredInsertCount();
                if (entryRequiredInsertCount > requiredInsertCount)
                    requiredInsertCount = entryRequiredInsertCount;
            }
        }

        DynamicTable dynamicTable = _context.getDynamicTable();
        int base = dynamicTable.getBase();
        int encodedInsertCount = encodeInsertCount(requiredInsertCount, dynamicTable.getCapacity());
        boolean signBit = base < requiredInsertCount;
        int deltaBase = signBit ? requiredInsertCount - base - 1 : base - requiredInsertCount;

        // TODO: Calculate the size required.
        ByteBuffer buffer = _bufferPool.acquire(1024, false);
        int pos = BufferUtil.flipToFill(buffer);

        // Encode the Field Section Prefix into the ByteBuffer.
        NBitInteger.encode(buffer, 8, encodedInsertCount);
        buffer.put(signBit ? (byte)0x80 : (byte)0x00);
        NBitInteger.encode(buffer, 7, deltaBase);

        // Encode the field lines into the ByteBuffer.
        for (EncodableEntry entry : encodableEntries)
        {
            entry.encode(buffer, base);
        }

        BufferUtil.flipToFlush(buffer, pos);
        return buffer;
    }

    private EncodableEntry encode(StreamInfo streamInfo, HttpField field)
    {
        DynamicTable dynamicTable = _context.getDynamicTable();

        if (field.getValue() == null)
            field = new HttpField(field.getHeader(), field.getName(), "");

        // TODO:
        //  1. The field.getHeader() could be null.
        //  3. Handle pre-encoded HttpFields.

        boolean canCreateEntry = shouldIndex(field) && (Entry.getSize(field) <= dynamicTable.getSpace());

        Entry entry = _context.get(field);
        if (entry != null && referenceEntry(entry, streamInfo))
        {
            return new EncodableEntry(entry);
        }
        else
        {
            // Should we duplicate this entry.
            if (entry != null && dynamicTable.canReference(entry) && canCreateEntry)
            {
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _handler.onInstruction(new DuplicateInstruction(entry.getIndex()));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return new EncodableEntry(newEntry);
            }
        }

        boolean huffman = shouldHuffmanEncode(field);
        Entry nameEntry = _context.get(field.getName());
        if (nameEntry != null && referenceEntry(nameEntry, streamInfo))
        {
            // Should we copy this entry
            if (canCreateEntry)
            {
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _handler.onInstruction(new IndexedNameEntryInstruction(!nameEntry.isStatic(), nameEntry.getIndex(), huffman, field.getValue()));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return new EncodableEntry(newEntry);
            }

            return new EncodableEntry(nameEntry, field);
        }
        else
        {
            if (canCreateEntry)
            {
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _handler.onInstruction(new LiteralNameEntryInstruction(huffman, field.getName(), huffman, field.getValue()));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return new EncodableEntry(newEntry);
            }

            return new EncodableEntry(field);
        }
    }

    public boolean insert(HttpField field)
    {
        DynamicTable dynamicTable = _context.getDynamicTable();
        if (field.getValue() == null)
            field = new HttpField(field.getHeader(), field.getName(), "");

        boolean canCreateEntry = shouldIndex(field) && (Entry.getSize(field) <= dynamicTable.getSpace());
        if (!canCreateEntry)
            return false;

        Entry newEntry = new Entry(field);
        dynamicTable.add(newEntry);

        Entry entry = _context.get(field);
        if (referenceEntry(entry))
        {
            _handler.onInstruction(new DuplicateInstruction(entry.getIndex()));
            return true;
        }

        boolean huffman = shouldHuffmanEncode(field);
        Entry nameEntry = _context.get(field.getName());
        if (referenceEntry(nameEntry))
        {
            _handler.onInstruction(new IndexedNameEntryInstruction(!nameEntry.isStatic(), nameEntry.getIndex(), huffman, field.getValue()));
            return true;
        }

        _handler.onInstruction(new LiteralNameEntryInstruction(huffman, field.getName(), huffman, field.getValue()));
        return true;
    }

    public static int encodeInsertCount(int reqInsertCount, int maxTableCapacity)
    {
        if (reqInsertCount == 0)
            return 0;

        int maxEntries = maxTableCapacity / 32;
        return (reqInsertCount % (2 * maxEntries)) + 1;
    }
}