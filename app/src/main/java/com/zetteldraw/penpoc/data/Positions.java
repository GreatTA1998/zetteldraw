package com.zetteldraw.penpoc.data;

/**
 * Fractional position keys ("a0", "a0V", "a1", ...). Keys sort by plain
 * byte order, so SQLite's BINARY collation and Postgres {@code COLLATE "C"}
 * agree with {@link String#compareTo}. Inserting between two keys never
 * rewrites any other row.
 *
 * Port of the widely used base-62 fractional-indexing algorithm.
 */
public final class Positions {
    static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char ZERO = DIGITS.charAt(0);
    private static final String SMALLEST_INTEGER = "A" + "0".repeat(26);

    private Positions() {
    }

    /** A key strictly between {@code a} and {@code b}; either may be null for an open end. */
    public static String between(String a, String b) {
        if (a != null) {
            validate(a);
        }
        if (b != null) {
            validate(b);
        }
        if (a != null && b != null && a.compareTo(b) >= 0) {
            throw new IllegalArgumentException(a + " >= " + b);
        }
        if (a == null) {
            if (b == null) {
                return "a" + ZERO;
            }
            String ib = integerPart(b);
            String fb = b.substring(ib.length());
            if (ib.equals(SMALLEST_INTEGER)) {
                return ib + midpoint("", fb);
            }
            if (ib.compareTo(b) < 0) {
                return ib;
            }
            String res = decrementInteger(ib);
            if (res == null) {
                throw new IllegalStateException("cannot decrement any more");
            }
            return res;
        }
        if (b == null) {
            String ia = integerPart(a);
            String fa = a.substring(ia.length());
            String i = incrementInteger(ia);
            return i == null ? ia + midpoint(fa, null) : i;
        }
        String ia = integerPart(a);
        String fa = a.substring(ia.length());
        String ib = integerPart(b);
        String fb = b.substring(ib.length());
        if (ia.equals(ib)) {
            return ia + midpoint(fa, fb);
        }
        String i = incrementInteger(ia);
        if (i == null) {
            throw new IllegalStateException("cannot increment any more");
        }
        if (i.compareTo(b) < 0) {
            return i;
        }
        return ia + midpoint(fa, null);
    }

    /** Key that sorts after {@code last} (null when the list is empty). */
    public static String after(String last) {
        return between(last, null);
    }

    private static String midpoint(String a, String b) {
        if (b != null && a.compareTo(b) >= 0) {
            throw new IllegalArgumentException(a + " >= " + b);
        }
        if ((!a.isEmpty() && a.charAt(a.length() - 1) == ZERO)
                || (b != null && !b.isEmpty() && b.charAt(b.length() - 1) == ZERO)) {
            throw new IllegalArgumentException("trailing zero");
        }
        if (b != null) {
            int n = 0;
            while (n < b.length() && (n < a.length() ? a.charAt(n) : ZERO) == b.charAt(n)) {
                n++;
            }
            if (n > 0) {
                return b.substring(0, n) + midpoint(
                        n < a.length() ? a.substring(n) : "", b.substring(n));
            }
        }
        int digitA = a.isEmpty() ? 0 : DIGITS.indexOf(a.charAt(0));
        int digitB = b != null ? DIGITS.indexOf(b.charAt(0)) : DIGITS.length();
        if (digitB - digitA > 1) {
            int mid = (int) Math.round(0.5 * (digitA + digitB));
            return String.valueOf(DIGITS.charAt(mid));
        }
        if (b != null && b.length() > 1) {
            return b.substring(0, 1);
        }
        return DIGITS.charAt(digitA) + midpoint(a.isEmpty() ? "" : a.substring(1), null);
    }

    private static int integerLength(char head) {
        if (head >= 'a' && head <= 'z') {
            return head - 'a' + 2;
        }
        if (head >= 'A' && head <= 'Z') {
            return 'Z' - head + 2;
        }
        throw new IllegalArgumentException("invalid position head: " + head);
    }

    private static String integerPart(String key) {
        int len = integerLength(key.charAt(0));
        if (len > key.length()) {
            throw new IllegalArgumentException("invalid position: " + key);
        }
        return key.substring(0, len);
    }

    static void validate(String key) {
        if (key.isEmpty() || key.equals(SMALLEST_INTEGER)) {
            throw new IllegalArgumentException("invalid position: " + key);
        }
        String i = integerPart(key);
        String f = key.substring(i.length());
        if (!f.isEmpty() && f.charAt(f.length() - 1) == ZERO) {
            throw new IllegalArgumentException("invalid position: " + key);
        }
        for (int k = 1; k < key.length(); k++) {
            if (DIGITS.indexOf(key.charAt(k)) < 0) {
                throw new IllegalArgumentException("invalid position: " + key);
            }
        }
    }

    private static String incrementInteger(String x) {
        char head = x.charAt(0);
        char[] digs = x.substring(1).toCharArray();
        boolean carry = true;
        for (int i = digs.length - 1; carry && i >= 0; i--) {
            int d = DIGITS.indexOf(digs[i]) + 1;
            if (d == DIGITS.length()) {
                digs[i] = ZERO;
            } else {
                digs[i] = DIGITS.charAt(d);
                carry = false;
            }
        }
        if (!carry) {
            return head + new String(digs);
        }
        if (head == 'Z') {
            return "a" + ZERO;
        }
        if (head == 'z') {
            return null;
        }
        char h = (char) (head + 1);
        String rest = new String(digs);
        rest = h > 'a' ? rest + ZERO : rest.substring(0, rest.length() - 1);
        return h + rest;
    }

    private static String decrementInteger(String x) {
        char head = x.charAt(0);
        char[] digs = x.substring(1).toCharArray();
        char last = DIGITS.charAt(DIGITS.length() - 1);
        boolean borrow = true;
        for (int i = digs.length - 1; borrow && i >= 0; i--) {
            int d = DIGITS.indexOf(digs[i]) - 1;
            if (d == -1) {
                digs[i] = last;
            } else {
                digs[i] = DIGITS.charAt(d);
                borrow = false;
            }
        }
        if (!borrow) {
            return head + new String(digs);
        }
        if (head == 'a') {
            return "Z" + last;
        }
        if (head == 'A') {
            return null;
        }
        char h = (char) (head - 1);
        String rest = new String(digs);
        rest = h < 'Z' ? rest + last : rest.substring(0, rest.length() - 1);
        return h + rest;
    }
}
