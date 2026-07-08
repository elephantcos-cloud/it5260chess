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

// Adapted for J2ME/CLDC 1.1 (it5260 build). Changes in this file:
//  - java.util.List/ArrayList -> Vector, using CLDC Vector's actual method
//    names (addElement/elementAt/setElementAt), not the add()/get()/set()
//    names List uses - CLDC's Vector predates the Collections-framework
//    retrofit that gave Vector those aliases.
//  - CLDC has no autoboxing (that's Java 5+ too), so anywhere a primitive
//    (long, int) went into a collection, it's now manually wrapped with
//    `new Long(...)`/`new Integer(...)` and unwrapped with .longValue()/
//    .intValue().
//  - StringBuilder -> StringBuffer (CLDC only has the latter).
//  - Two array-based enhanced-for loops (for (TTEntry ent : table)) changed
//    to indexed loops for consistency/safety with older toolchains, even
//    though enhanced-for over a plain array doesn't strictly need Iterable.
//  - printStats() is debug-only output; simplified from printf's %.2f-style
//    formatting to plain concatenation rather than hand-rolling a decimal
//    formatter for a diagnostic method nothing else calls.
//  - IMPORTANT: the constructor takes log2Size, and the Android build uses
//    setTTLogSize(15) - a 32768-entry table, roughly 1MB. That's much too
//    big for this device's RAM budget; see PORTING_NOTES.md for the smaller
//    value used instead (also fixed at the ComputerPlayer.java call site).
import java.util.Vector;

public class TranspositionTable {
    static final public class TTEntry {
        long key;               // Zobrist hash key
        private short move;     // from + (to<<6) + (promote<<12)
        private short score;    // Score from search
        private short depthSlot; // Search depth (bit 0-14) and hash slot (bit 15).
        byte generation;        // Increase when OTB position changes
        public byte type;       // exact score, lower bound, upper bound
        short evalScore;        // Score from static evaluation 

        static public final int T_EXACT = 0;   // Exact score
        static public final int T_GE = 1;      // True score >= this.score
        static public final int T_LE = 2;      // True score <= this.score
        static public final int T_EMPTY = 3;   // Empty hash slot
        
        /** Return true if this object is more valuable than the other, false otherwise. */
        public final boolean betterThan(TTEntry other, int currGen) {
            if ((generation == currGen) != (other.generation == currGen)) {
                return generation == currGen;   // Old entries are less valuable
            }
            if ((type == T_EXACT) != (other.type == T_EXACT)) {
                return type == T_EXACT;         // Exact score more valuable than lower/upper bound
            }
            if (getDepth() != other.getDepth()) {
                return getDepth() > other.getDepth();     // Larger depth is more valuable
            }
            return false;   // Otherwise, pretty much equally valuable
        }

        /** Return true if entry is good enough to spend extra time trying to avoid overwriting it. */
        public final boolean valuable(int currGen) {
            if (generation != currGen)
                return false;
            return (type == T_EXACT) || (getDepth() > 3 * Search.plyScale);
        }

        public final void getMove(Move m) {
            m.from = move & 63;
            m.to = (move >> 6) & 63;
            m.promoteTo = (move >> 12) & 15;
        }
        public final void setMove(Move move) {
            this.move = (short)(move.from + (move.to << 6) + (move.promoteTo << 12));
        }
        
        /** Get the score from the hash entry and convert from "mate in x" to "mate at ply". */
        public final int getScore(int ply) {
            int sc = score;
            if (sc > Search.MATE0 - 1000) {
                sc -= ply;
            } else if (sc < -(Search.MATE0 - 1000)) {
                sc += ply;
            }
            return sc;
        }
        
        /** Convert score from "mate at ply" to "mate in x" and store in hash entry. */
        public final void setScore(int score, int ply) {
            if (score > Search.MATE0 - 1000) {
                score += ply;
            } else if (score < -(Search.MATE0 - 1000)) {
                score -= ply;
            }
            this.score = (short)score;
        }

        /** Get depth from the hash entry. */
        public final int getDepth() {
            return depthSlot & 0x7fff;
        }

        /** Set depth. */
        public final void setDepth(int d) {
            depthSlot &= 0x8000;
            depthSlot |= ((short)d) & 0x7fff;
        }

        final int getHashSlot() {
            return depthSlot >>> 15;
        }

        public final void setHashSlot(int s) {
            depthSlot &= 0x7fff;
            depthSlot |= (s << 15);
        }
    }
    private TTEntry[] table;
    private TTEntry emptySlot;
    private byte generation;

    /** Constructor. Creates an empty transposition table with numEntries slots. */
    public TranspositionTable(int log2Size) {
        final int numEntries = (1 << log2Size);
        table = new TTEntry[numEntries];
        for (int i = 0; i < numEntries; i++) {
            TTEntry ent = new TTEntry();
            ent.key = 0;
            ent.depthSlot = 0;
            ent.type = TTEntry.T_EMPTY;
            table[i] = ent;
        }
        emptySlot = new TTEntry();
        emptySlot.type = TTEntry.T_EMPTY;
        generation = 0;
    }

    public final void insert(long key, Move sm, int type, int ply, int depth, int evalScore) {
        if (depth < 0) depth = 0;
        int idx0 = h0(key);
        int idx1 = h1(key);
        TTEntry ent = table[idx0];
        byte hashSlot = 0;
        if (ent.key != key) {
            ent = table[idx1];
            hashSlot = 1;
        }
        if (ent.key != key) {
            if (table[idx1].betterThan(table[idx0], generation)) {
                ent = table[idx0];
                hashSlot = 0;
            }
            if (ent.valuable(generation)) {
                int altEntIdx = (ent.getHashSlot() == 0) ? h1(ent.key) : h0(ent.key);
                if (ent.betterThan(table[altEntIdx], generation)) {
                    TTEntry altEnt = table[altEntIdx];
                    altEnt.key = ent.key;
                    altEnt.move = ent.move;
                    altEnt.score = ent.score;
                    altEnt.depthSlot = ent.depthSlot;
                    altEnt.generation = ent.generation;
                    altEnt.type = ent.type;
                    altEnt.setHashSlot(1 - ent.getHashSlot());
                    altEnt.evalScore = ent.evalScore;
                }
            }
        }
        boolean doStore = true;
        if ((ent.key == key) && (ent.getDepth() > depth) && (ent.type == type)) {
            if (type == TTEntry.T_EXACT) {
                doStore = false;
            } else if ((type == TTEntry.T_GE) && (sm.score <= ent.getScore(ply))) {
                doStore = false;
            } else if ((type == TTEntry.T_LE) && (sm.score >= ent.getScore(ply))) {
                doStore = false;
            }
        }
        if (doStore) {
            if ((ent.key != key) || (sm.from != sm.to))
                ent.setMove(sm);
            ent.key = key;
            ent.setScore(sm.score, ply);
            ent.setDepth(depth);
            ent.generation = generation;
            ent.type = (byte)type;
            ent.setHashSlot(hashSlot);
            ent.evalScore = (short)evalScore;
        }
    }

    /** Retrieve an entry from the hash table corresponding to "pos". */
    public final TTEntry probe(long key) {
        int idx0 = h0(key);
        TTEntry ent = table[idx0];
        if (ent.key == key) {
            ent.generation = generation;
            return ent;
        }
        int idx1 = h1(key);
        ent = table[idx1];
        if (ent.key == key) {
            ent.generation = generation;
            return ent;
        }
        return emptySlot;
    }

    /**
     * Increase hash table generation. This means that subsequent inserts will be considered
     * more valuable than the entries currently present in the hash table.
     */
    public final void nextGeneration() {
        generation++;
    }

    /** Clear the transposition table. */
    public final void clear() {
        for (int i = 0; i < table.length; i++) {
            table[i].type = TTEntry.T_EMPTY;
        }
    }

    /**
     * Extract a list of PV moves, starting from "rootPos" and first move "m".
     */
    public final Vector extractPVMoves(Position rootPos, Move m) {
        Position pos = new Position(rootPos);
        m = new Move(m);
        Vector ret = new Vector();
        UndoInfo ui = new UndoInfo();
        Vector hashHistory = new Vector();
        MoveGen moveGen = new MoveGen();
        while (true) {
            ret.addElement(m);
            pos.makeMove(m, ui);
            if (hashHistory.contains(new Long(pos.zobristHash()))) {
                break;
            }
            hashHistory.addElement(new Long(pos.zobristHash()));
            TTEntry ent = probe(pos.historyHash());
            if (ent.type == TTEntry.T_EMPTY) {
                break;
            }
            m = new Move(0,0,0);
            ent.getMove(m);
            MoveGen.MoveList moves = moveGen.pseudoLegalMoves(pos);
            MoveGen.removeIllegal(pos, moves);
            boolean contains = false;
            for (int mi = 0; mi < moves.size; mi++)
                if (moves.m[mi].equals(m)) {
                    contains = true;
                    break;
                }
            if  (!contains)
                break;
        }
        return ret;
    }

    /** Extract the PV starting from pos, using hash entries, both exact scores and bounds. */
    public final String extractPV(Position pos) {
        StringBuffer ret = new StringBuffer(100);
        pos = new Position(pos);    // To avoid modifying the input parameter
        boolean first = true;
        TTEntry ent = probe(pos.historyHash());
        UndoInfo ui = new UndoInfo();
        Vector hashHistory = new Vector();
        boolean repetition = false;
        MoveGen moveGen = MoveGen.instance;
        while (ent.type != TTEntry.T_EMPTY) {
            String type = "";
            if (ent.type == TTEntry.T_LE) {
                type = "<";
            } else if (ent.type == TTEntry.T_GE) {
                type = ">";
            }
            Move m = new Move(0,0,0);
            ent.getMove(m);
            MoveGen.MoveList moves = moveGen.pseudoLegalMoves(pos);
            MoveGen.removeIllegal(pos, moves);
            boolean contains = false;
            for (int mi = 0; mi < moves.size; mi++)
                if (moves.m[mi].equals(m)) {
                    contains = true;
                    break;
                }
            if  (!contains)
                break;
            String moveStr = TextIO.moveToString(pos, m, false);
            if (repetition)
                break;
            if (!first) {
                ret.append(" ");
            }
            ret.append(type);
            ret.append(moveStr);
            pos.makeMove(m, ui);
            if (hashHistory.contains(new Long(pos.zobristHash()))) {
                repetition = true;
            }
            hashHistory.addElement(new Long(pos.zobristHash()));
            ent = probe(pos.historyHash());
            first = false;
        }
        return ret.toString();
    }

    /** Print hash table statistics. */
    public final void printStats() {
        int unused = 0;
        int thisGen = 0;
        Vector depHist = new Vector();
        final int maxDepth = 20*8;
        for (int i = 0; i < maxDepth; i++) {
            depHist.addElement(new Integer(0));
        }
        for (int i = 0; i < table.length; i++) {
            TTEntry ent = table[i];
            if (ent.type == TTEntry.T_EMPTY) {
                unused++;
            } else {
                if (ent.generation == generation) {
                    thisGen++;
                }
                if (ent.getDepth() < maxDepth) {
                    int d = ent.getDepth();
                    int newCount = ((Integer) depHist.elementAt(d)).intValue() + 1;
                    depHist.setElementAt(new Integer(newCount), d);
                }
            }
        }
        double w = 100.0 / table.length;
        // Simplified from the original's printf("%.2f%%", ...) - debug-only
        // output, not worth hand-rolling a decimal formatter for (CLDC has
        // no Formatter/printf at all).
        System.out.println("Hash stats: size:" + table.length + " unused:" + unused +
                            " thisGen:" + thisGen);
        for (int i = 0; i < maxDepth; i++) {
            int c = ((Integer) depHist.elementAt(i)).intValue();
            if (c > 0)
                System.out.println(i + " " + c + " (" + (c*w) + "%)");
        }
    }
    
    private int h0(long key) {
        return (int)(key & (table.length - 1));
    }
    
    private int h1(long key) {
        return (int)((key >> 32) & (table.length - 1));
    }
}
