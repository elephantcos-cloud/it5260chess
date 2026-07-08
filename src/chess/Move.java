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

// Adapted for J2ME/CLDC 1.1 (it5260 build). Changes from the original:
//  - Removed "import java.util.Comparator" and "implements Comparator<Move>":
//    CLDC 1.1 has no java.util.Comparator and no generics. SortByScore is now
//    a plain helper with a compare(Move,Move) method, used by a manual sort
//    routine (Vector has no built-in Comparator-based sort in CLDC).
//  - Removed @Override annotations for safety with older CLDC preverify tools.

package chess;

public class Move {
    /** From square, 0-63. */
    public int from;

    /** To square, 0-63. */
    public int to;

    /** Promotion piece. */
    public int promoteTo;

    public int score;

    /** Create a move object. */
    public Move(int from, int to, int promoteTo) {
        this.from = from;
        this.to = to;
        this.promoteTo = promoteTo;
        this.score = 0;
    }

    public Move(int from, int to, int promoteTo, int score) {
        this.from = from;
        this.to = to;
        this.promoteTo = promoteTo;
        this.score = score;
    }

    /** Used for manual move-list sorting; no longer implements java.util.Comparator. */
    static public class SortByScore {
        public int compare(Move sm1, Move sm2) {
            return sm2.score - sm1.score;
        }
    }

    public Move(Move m) {
        this.from = m.from;
        this.to = m.to;
        this.promoteTo = m.promoteTo;
        this.score = m.score;
    }

    public final void copyFrom(Move m) {
        from      = m.from;
        to        = m.to;
        promoteTo = m.promoteTo;
//        score = m.score;
    }

    public final void clear() {
        from      = 0;
        to        = 0;
        promoteTo = 0;
        score     = 0;
    }

    public final void setMove(int from, int to, int promoteTo, int score) {
        this.from = from;
        this.to = to;
        this.promoteTo = promoteTo;
        this.score = score;
    }

    /** Note that score is not included in the comparison. */
    public boolean equals(Object o) {
        if ((o == null) || (o.getClass() != this.getClass()))
            return false;
        Move other = (Move)o;
        if (from != other.from)
            return false;
        if (to != other.to)
            return false;
        if (promoteTo != other.promoteTo)
            return false;
        return true;
    }

    public int hashCode() {
        return (from * 64 + to) * 16 + promoteTo;
    }

    /** Useful for debugging. */
    public final String toString() {
        return TextIO.moveToUCIString(this);
    }
}
