/*
    CuckooChess - A java chess program.
    Copyright (C) 2011  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package chess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

// Adapted for J2ME/CLDC 1.1 (it5260 build). Changes in this file:
//  - SecureRandom -> Random (never security-sensitive here either).
//  - Map<Long,List<BookEntry>> -> Hashtable, with keys manually boxed as
//    Long (no autoboxing in CLDC) and values as Vector instead of List.
//  - The List<Byte> that book.bin got read into (boxing every single byte
//    as an Object!) is now a plain ByteArrayOutputStream -> byte[], which
//    is both CLDC-correct and considerably more efficient.
//  - try-with-resources -> try/finally (Java 7+, not in CLDC).
//  - One enhanced-for over a Vector (getAllBookMoves) -> indexed loop.
//  - printf -> println, StringBuilder -> StringBuffer.
//  - Confirmed this doesn't need any Zobrist-hash compatibility with the
//    original engine: book.bin only stores a flat sequence of moves, replayed
//    from the start position to rebuild bookMap with whatever hash function
//    is compiled in (ours) - both build and lookup happen at runtime with
//    the same function, so nothing about switching Position's hash-key
//    generator (see Position.java) affects this file at all.

/** Implements an opening book. */
public class Book {
    public static class BookEntry {
        Move move;
        int count;
        BookEntry(Move move) {
            this.move = move;
            count = 1;
        }
    }
    private static Hashtable bookMap;
    private static Random rndGen;
    private static int numBookMoves = -1;
    private boolean verbose;

    public Book(boolean verbose) {
        this.verbose = verbose;
    }

    private void initBook() {
        if (numBookMoves >= 0)
            return;
        long t0 = System.currentTimeMillis();
        bookMap = new Hashtable();
        rndGen = new Random(System.currentTimeMillis());
        numBookMoves = 0;
        InputStream inStream = getClass().getResourceAsStream("/book.bin");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] tmpBuf = new byte[1024];
            while (true) {
                int len = inStream.read(tmpBuf);
                if (len <= 0) break;
                bos.write(tmpBuf, 0, len);
            }
            byte[] buf = bos.toByteArray();
            Position startPos = TextIO.readFEN(TextIO.startPosFEN);
            Position pos = new Position(startPos);
            UndoInfo ui = new UndoInfo();
            int len = buf.length;
            for (int i = 0; i < len; i += 2) {
                int b0 = buf[i]; if (b0 < 0) b0 += 256;
                int b1 = buf[i+1]; if (b1 < 0) b1 += 256;
                int move = (b0 << 8) + b1;
                if (move == 0) {
                    pos = new Position(startPos);
                } else {
                    boolean bad = ((move >> 15) & 1) != 0;
                    int prom = (move >> 12) & 7;
                    Move m = new Move(move & 63, (move >> 6) & 63,
                                      promToPiece(prom, pos.whiteMove));
                    if (!bad)
                        addToBook(pos, m);
                    pos.makeMove(m, ui);
                }
            }
        } catch (ChessParseError ex) {
            throw new RuntimeException();
        } catch (IOException ex) {
            System.out.println("Can't read opening book resource");
            throw new RuntimeException();
        } finally {
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (IOException e) {
                    // ignore close failure
                }
            }
        }
        if (verbose) {
            long t1 = System.currentTimeMillis();
            System.out.println("Book moves:" + numBookMoves + " (parse time:" +
                    ((t1 - t0) / 1000.0) + ")");
        }
    }

    /** Add a move to a position in the opening book. */
    private void addToBook(Position pos, Move moveToAdd) {
        Long key = new Long(pos.zobristHash());
        Vector ent = (Vector) bookMap.get(key);
        if (ent == null) {
            ent = new Vector();
            bookMap.put(key, ent);
        }
        for (int i = 0; i < ent.size(); i++) {
            BookEntry be = (BookEntry) ent.elementAt(i);
            if (be.move.equals(moveToAdd)) {
                be.count++;
                return;
            }
        }
        BookEntry be = new BookEntry(moveToAdd);
        ent.addElement(be);
        numBookMoves++;
    }

    /** Return a random book move for a position, or null if out of book. */
    public final Move getBookMove(Position pos) {
        initBook();
        Vector bookMoves = (Vector) bookMap.get(new Long(pos.zobristHash()));
        if (bookMoves == null) {
            return null;
        }
        
        MoveGen.MoveList legalMoves = new MoveGen().pseudoLegalMoves(pos);
        MoveGen.removeIllegal(pos, legalMoves);
        int sum = 0;
        for (int i = 0; i < bookMoves.size(); i++) {
            BookEntry be = (BookEntry) bookMoves.elementAt(i);
            boolean contains = false;
            for (int mi = 0; mi < legalMoves.size; mi++)
                if (legalMoves.m[mi].equals(be.move)) {
                    contains = true;
                    break;
                }
            if  (!contains) {
                // If an illegal move was found, it means there was a hash collision.
                return null;
            }
            sum += getWeight(be.count);
        }
        if (sum <= 0) {
            return null;
        }
        int rnd = rndGen.nextInt(sum);
        sum = 0;
        for (int i = 0; i < bookMoves.size(); i++) {
            BookEntry be = (BookEntry) bookMoves.elementAt(i);
            sum += getWeight(be.count);
            if (rnd < sum) {
                return be.move;
            }
        }
        // Should never get here
        throw new RuntimeException();
    }

    private int getWeight(int count) {
        double tmp = Math.sqrt(count);
        return (int)(tmp * Math.sqrt(tmp) * 100 + 1);
    }

    /** Return a string describing all book moves. */
    public final String getAllBookMoves(Position pos) {
        initBook();
        StringBuffer ret = new StringBuffer();
        Vector bookMoves = (Vector) bookMap.get(new Long(pos.zobristHash()));
        if (bookMoves != null) {
            for (int i = 0; i < bookMoves.size(); i++) {
                BookEntry be = (BookEntry) bookMoves.elementAt(i);
                String moveStr = TextIO.moveToString(pos, be.move, false);
                ret.append(moveStr);
                ret.append("(");
                ret.append(be.count);
                ret.append(") ");
            }
        }
        return ret.toString();
    }

    private static int promToPiece(int prom, boolean whiteMove) {
        switch (prom) {
        case 1: return whiteMove ? Piece.WQUEEN : Piece.BQUEEN;
        case 2: return whiteMove ? Piece.WROOK  : Piece.BROOK;
        case 3: return whiteMove ? Piece.WBISHOP : Piece.BBISHOP;
        case 4: return whiteMove ? Piece.WKNIGHT : Piece.BKNIGHT;
        default: return Piece.EMPTY;
        }
    }
}
