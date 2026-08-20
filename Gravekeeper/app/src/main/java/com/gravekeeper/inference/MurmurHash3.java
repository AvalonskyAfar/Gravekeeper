package com.gravekeeper.inference;

final class MurmurHash3 {
    private MurmurHash3() {}

    static int hash32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int hash = seed;
        int roundedEnd = data.length & 0xfffffffc;

        for (int index = 0; index < roundedEnd; index += 4) {
            int value = (data[index] & 0xff)
                    | ((data[index + 1] & 0xff) << 8)
                    | ((data[index + 2] & 0xff) << 16)
                    | (data[index + 3] << 24);
            value *= c1;
            value = Integer.rotateLeft(value, 15);
            value *= c2;

            hash ^= value;
            hash = Integer.rotateLeft(hash, 13);
            hash = hash * 5 + 0xe6546b64;
        }

        int tail = 0;
        switch (data.length & 3) {
            case 3:
                tail ^= (data[roundedEnd + 2] & 0xff) << 16;
            case 2:
                tail ^= (data[roundedEnd + 1] & 0xff) << 8;
            case 1:
                tail ^= data[roundedEnd] & 0xff;
                tail *= c1;
                tail = Integer.rotateLeft(tail, 15);
                tail *= c2;
                hash ^= tail;
            default:
                break;
        }

        hash ^= data.length;
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        hash ^= hash >>> 16;
        return hash;
    }
}
