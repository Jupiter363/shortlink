package com.jupiter.shortlink.id;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;

class ShortCodeCodecTest {
    static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        return key;
    }

    @Test
    void frozenVectorsFromIndependentUnsigned64BitReference() {
        ShortCodeCodec codec = new ShortCodeCodec(key());
        long[] ids = {1, 2, 62, 67_108_863, 67_108_864, IdRange.ID_LIMIT - 1};
        String[] expected = {
            "1ehusmDXL", "A6HTUQmEz", "Jr5z0yO89", "A7PT8lf59", "CwXGuknxE", "FR0nftDCp"
        };
        for (int i = 0; i < ids.length; i++) {
            assertEquals(expected[i], codec.encode(ids[i]));
            assertEquals(ids[i], codec.decode(expected[i]));
        }
    }

    @Test
    void inverseForEdgesAndDeterministicSample() {
        ShortCodeCodec codec = new ShortCodeCodec(key());
        HashSet<String> outputs = new HashSet<>();
        for (long id = 1; id <= 5_000; id++) {
            String code = codec.encode(id);
            assertTrue(outputs.add(code));
            assertEquals(9, code.length());
            assertEquals(id, codec.decode(code));
        }
        Random random = new Random(42);
        for (int i = 0; i < 5_000; i++) {
            long id = 1 + Math.floorMod(random.nextLong(), IdRange.ID_LIMIT - 1);
            assertEquals(id, codec.decode(codec.encode(id)));
        }
    }

    @Test
    void rejectsOutsideDomainAndCopiesFixedKey() {
        byte[] key = key();
        ShortCodeCodec codec = new ShortCodeCodec(key);
        key[0]++;
        assertEquals("1ehusmDXL", codec.encode(1));
        assertThrows(IllegalArgumentException.class, () -> new ShortCodeCodec(new byte[16]));
        for (long id : new long[] {-1, 0, IdRange.ID_LIMIT, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> codec.encode(id));
        }
        for (String code : new String[] {"", "abc", "zzzzzzzzz", "!!!!!!!!!", "B2opIzNrp"}) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(code));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(null));
        assertNotEquals(codec.decode("1ehusmDXL"), codec.decode("1eHusmDXL"));
    }
}
