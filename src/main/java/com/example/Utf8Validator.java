package com.example;

import jdk.incubator.vector.*;

import java.util.Arrays;
import java.util.Objects;

public final class Utf8Validator {
    private static final byte TOO_SHORT = (byte) 1;
    private static final byte TOO_LONG = (byte) (1 << 1);
    private static final byte OVERLONG_3 = (byte) (1 << 2);
    private static final byte TOO_LARGE = (byte) (1 << 3);
    private static final byte SURROGATE = (byte) (1 << 4);
    private static final byte OVERLONG_2 = (byte) (1 << 5);
    private static final byte TOO_LARGE_1000 = (byte) (1 << 6);
    private static final byte OVERLONG_4 = (byte) (1 << 6);
    private static final byte TWO_CONTS = (byte) (1 << 7);
    private static final byte CARRY = (byte) (TOO_SHORT | TOO_LONG | TWO_CONTS);

    private static final byte MAX_2_BYTE_LEAD = (byte) 0xDF;
    private static final byte MAX_3_BYTE_LEAD = (byte) 0xEF;
    private static final byte LOW_NIBBLE_MASK = (byte) 0x0F;
    private static final byte ALL_ASCII_MASK = (byte) 0x80;

    private static final VectorSpecies<Integer> INT_SPECIES;
    private static final VectorSpecies<Byte> BYTE_SPECIES;
    private static final ByteVector BYTE1_HIGH_TABLE;
    private static final ByteVector BYTE1_LOW_TABLE;
    private static final ByteVector BYTE2_HIGH_TABLE;
    private static final ByteVector INCOMPLETE;
    private static final VectorShuffle<Integer> FOUR_BYTES_FORWARD_SHIFT;

    static {
        int vecSize = Integer.parseInt(System.getProperty("jing.marshalljson.utf8validator.vecsize", "-1"));
        if (vecSize < 0) {
            vecSize = IntVector.SPECIES_PREFERRED.vectorBitSize();
        }
        switch (vecSize) {
            case 64 -> {
                INT_SPECIES = IntVector.SPECIES_64;
                BYTE_SPECIES = ByteVector.SPECIES_64;
            }
            case 128 -> {
                INT_SPECIES = IntVector.SPECIES_128;
                BYTE_SPECIES = ByteVector.SPECIES_128;
            }
            case 256 -> {
                INT_SPECIES = IntVector.SPECIES_256;
                BYTE_SPECIES = ByteVector.SPECIES_256;
            }
            case 512 -> {
                INT_SPECIES = IntVector.SPECIES_512;
                BYTE_SPECIES = ByteVector.SPECIES_512;
            }
            default -> throw new ExceptionInInitializerError("unknown vector size : " + vecSize);
        }

        byte[] b1h = new byte[BYTE_SPECIES.length()];
        for (int i = 0; i < 8; i++) b1h[i] = TOO_LONG;
        for (int i = 8; i < 12; i++) b1h[i] = TWO_CONTS;
        b1h[12] = (byte) (TOO_SHORT | OVERLONG_2);
        b1h[13] = TOO_SHORT;
        b1h[14] = (byte) (TOO_SHORT | OVERLONG_3 | SURROGATE);
        b1h[15] = (byte) (TOO_SHORT | TOO_LARGE | TOO_LARGE_1000 | OVERLONG_4);
        BYTE1_HIGH_TABLE = ByteVector.fromArray(BYTE_SPECIES, b1h, 0);

        byte[] b1l = new byte[BYTE_SPECIES.length()];
        b1l[0] = (byte) (CARRY | OVERLONG_3 | OVERLONG_2 | OVERLONG_4);
        b1l[1] = (byte) (CARRY | OVERLONG_2);
        b1l[2] = CARRY;
        b1l[3] = CARRY;
        b1l[4] = (byte) (CARRY | TOO_LARGE);
        for (int i = 5; i <= 12; i++) {
            b1l[i] = (byte) (CARRY | TOO_LARGE | TOO_LARGE_1000);
        }
        b1l[13] = (byte) (CARRY | TOO_LARGE | TOO_LARGE_1000 | SURROGATE);
        b1l[14] = (byte) (CARRY | TOO_LARGE | TOO_LARGE_1000);
        b1l[15] = (byte) (CARRY | TOO_LARGE | TOO_LARGE_1000);
        BYTE1_LOW_TABLE = ByteVector.fromArray(BYTE_SPECIES, b1l, 0);

        byte[] b2h = new byte[BYTE_SPECIES.length()];
        for (int i = 0; i < 8; i++) {
            b2h[i] = TOO_SHORT;
        }
        b2h[8] = (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | OVERLONG_3 | TOO_LARGE_1000 | OVERLONG_4);
        b2h[9] = (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | OVERLONG_3 | TOO_LARGE);
        b2h[10] = (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | SURROGATE | TOO_LARGE);
        b2h[11] = (byte) (TOO_LONG | OVERLONG_2 | TWO_CONTS | SURROGATE | TOO_LARGE);
        for (int i = 12; i < 16; i++) {
            b2h[i] = TOO_SHORT;
        }
        BYTE2_HIGH_TABLE = ByteVector.fromArray(BYTE_SPECIES, b2h, 0);

        byte[] inc = new byte[BYTE_SPECIES.length()];
        Arrays.fill(inc, (byte) 255);
        inc[inc.length - 3] = (byte) 0xF0;
        inc[inc.length - 2] = (byte) 0xE0;
        inc[inc.length - 1] = (byte) 0xC0;
        INCOMPLETE = ByteVector.fromArray(BYTE_SPECIES, inc, 0);

        int[] idx = new int[INT_SPECIES.length()];
        idx[0] = INT_SPECIES.length() - 1;
        for (int i = 1; i < INT_SPECIES.length(); i++) {
            idx[i] = i - 1;
        }
        FOUR_BYTES_FORWARD_SHIFT = VectorShuffle.fromValues(INT_SPECIES, idx);
    }

    private Utf8Validator() {
        throw new UnsupportedOperationException("utility class");
    }

    public static boolean validate(byte[] bytes, int from, int to) {
        long errors = 0;
        long previousIncomplete = 0;
        int previousFourBytes = 0;
        for (; from <= to - BYTE_SPECIES.length(); from += BYTE_SPECIES.length()) {
            ByteVector chunk = ByteVector.fromArray(BYTE_SPECIES, bytes, from);
            IntVector chunkAsInts = chunk.reinterpretAsInts();
            if (chunk.and(ALL_ASCII_MASK).eq((byte) 0).allTrue()) {
                errors |= previousIncomplete;
            } else {
                previousIncomplete = chunk.compare(VectorOperators.UGE, INCOMPLETE).toLong();
                IntVector shifted = chunkAsInts.rearrange(FOUR_BYTES_FORWARD_SHIFT).withLane(0, previousFourBytes);
                ByteVector prev1 = chunkAsInts.lanewise(VectorOperators.LSHL, 8).or(shifted.lanewise(VectorOperators.LSHR, 24)).reinterpretAsBytes();
                ByteVector highNibbles2 = chunkAsInts.lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes().and(LOW_NIBBLE_MASK);
                ByteVector highNibbles1 = prev1.reinterpretAsInts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes().and(LOW_NIBBLE_MASK);
                ByteVector lowNibbles1 = prev1.and(LOW_NIBBLE_MASK);
                ByteVector sc = highNibbles1.selectFrom(BYTE1_HIGH_TABLE).and(lowNibbles1.selectFrom(BYTE1_LOW_TABLE)).and(highNibbles2.selectFrom(BYTE2_HIGH_TABLE));
                ByteVector prev2 = chunkAsInts.lanewise(VectorOperators.LSHL, 16).or(shifted.lanewise(VectorOperators.LSHR, 16)).reinterpretAsBytes();
                ByteVector prev3 = chunkAsInts.lanewise(VectorOperators.LSHL, 24).or(shifted.lanewise(VectorOperators.LSHR, 8)).reinterpretAsBytes();
                VectorMask<Byte> must23 = prev2.compare(VectorOperators.UGT, MAX_2_BYTE_LEAD).or(prev3.compare(VectorOperators.UGT, MAX_3_BYTE_LEAD));
                errors |= sc.add((byte) 0x80, must23).compare(VectorOperators.NE, (byte) 0).toLong();
            }
            previousFourBytes = chunkAsInts.lane(INT_SPECIES.length() - 1);
        }
        if (from < to) {
            ByteVector chunk = ByteVector.fromArray(BYTE_SPECIES, bytes, from, BYTE_SPECIES.indexInRange(from, to));
            IntVector chunkAsInts = chunk.reinterpretAsInts();
            if (!chunk.and(ALL_ASCII_MASK).eq((byte) 0).allTrue()) {
                previousIncomplete = chunk.compare(VectorOperators.UGE, INCOMPLETE).toLong();
                IntVector shifted = chunkAsInts.rearrange(FOUR_BYTES_FORWARD_SHIFT).withLane(0, previousFourBytes);
                ByteVector prev1 = chunkAsInts.lanewise(VectorOperators.LSHL, 8).or(shifted.lanewise(VectorOperators.LSHR, 24)).reinterpretAsBytes();
                ByteVector highNibbles2 = chunkAsInts.lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes().and(LOW_NIBBLE_MASK);
                ByteVector highNibbles1 = prev1.reinterpretAsInts().lanewise(VectorOperators.LSHR, 4).reinterpretAsBytes().and(LOW_NIBBLE_MASK);
                ByteVector lowNibbles1 = prev1.and(LOW_NIBBLE_MASK);
                ByteVector sc = highNibbles1.selectFrom(BYTE1_HIGH_TABLE).and(lowNibbles1.selectFrom(BYTE1_LOW_TABLE)).and(highNibbles2.selectFrom(BYTE2_HIGH_TABLE));
                ByteVector prev2 = chunkAsInts.lanewise(VectorOperators.LSHL, 16).or(shifted.lanewise(VectorOperators.LSHR, 16)).reinterpretAsBytes();
                ByteVector prev3 = chunkAsInts.lanewise(VectorOperators.LSHL, 24).or(shifted.lanewise(VectorOperators.LSHR, 8)).reinterpretAsBytes();
                VectorMask<Byte> must23 = prev2.compare(VectorOperators.UGT, MAX_2_BYTE_LEAD).or(prev3.compare(VectorOperators.UGT, MAX_3_BYTE_LEAD));
                errors |= sc.add((byte) 0x80, must23).compare(VectorOperators.NE, (byte) 0).toLong();
            }
        }
        return (errors | previousIncomplete) == 0L;
    }

    public static boolean scalarValidateHeap(byte[] bytes, int from, int to) {
        Objects.checkFromToIndex(from, to, bytes.length);
        int b1, b2;
        for (; ; ) {
            do {
                if (from >= to) {
                    return true;
                }
            } while ((b1 = bytes[from++]) >= 0);
            if (b1 < (byte) 0xE0) {
                if (from == to) {
                    return false;
                }
                if (b1 < (byte) 0xC2 || bytes[from++] > (byte) 0xBF) {
                    return false;
                }
            } else if (b1 < (byte) 0xF0) {
                if (to - from <= 1) {
                    return false;
                }
                b2 = bytes[from++];
                if (b2 > (byte) 0xBF
                        || (b1 == (byte) 0xE0 && b2 < (byte) 0xA0)
                        || (b1 == (byte) 0xED && b2 >= (byte) 0xA0)
                        || bytes[from++] > (byte) 0xBF) {
                    return false;
                }
            } else {
                if (to - from <= 2) {
                    return false;
                }
                b2 = bytes[from++];
                if (b2 > (byte) 0xBF
                        || (((b1 << 28) + (b2 - (byte) 0x90)) >> 30) != 0
                        || bytes[from++] > (byte) 0xBF
                        || bytes[from++] > (byte) 0xBF) {
                    return false;
                }
            }
        }
    }
}
