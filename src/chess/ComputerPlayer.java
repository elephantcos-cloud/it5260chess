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
//  - java.security.SecureRandom removed (not in CLDC) - findSemiRandomMove()
//    now uses plain java.util.Random, which is exactly as good here since
//    this was never a security-sensitive use of randomness.
//  - java.util.List -> Vector throughout (CLDC has no Collections/generics).
//  - The enhanced-for loop over `history` became an indexed loop, since CLDC's
//    Vector doesn't implement Iterable (that + enhanced-for both arrived in
//    Java 5, alongside the Collections framework CLDC doesn't have).
//  - String.isEmpty() -> length()==0 (isEmpty() is Java 6+, after CLDC 1.1).
//  - System.out.printf(...) -> println() + concatenation (printf/Formatter
//    isn't in CLDC either).
//  - assert(false) -> throw new RuntimeException(...): CLDC toolchains can't
//    be relied on to support the assert keyword's classfile attribute.
//  - Math.exp() in moveProbWeight() -> a precomputed lookup table (CLDC's
//    Math class has sqrt/abs/ceil/floor but no exp/log/sin/cos/pow - no
//    transcendental functions). The table was generated from the exact same
//    formula the original used, so the "weak mode" behavior is unchanged.
//  - Dropped searchPosition(): it returned a generic TwoReturnValues<Move,
//    String> used only by CuckooChess's own EPD test-suite runner, which we
//    aren't porting. getCommand() (the method our MIDlet UI will actually
//    call to get a move) doesn't use it. Flagging this in case a future test
//    harness wants it back - the original is one file fetch away on GitHub.
import java.util.Random;
import java.util.Vector;

/** A computer algorithm player. */
public class ComputerPlayer implements Player {
    public static final String engineName = "CuckooChess 1.13a9 (it5260)";

    private int minTimeMillis;
    int maxTimeMillis;
    int maxDepth;
    private int maxNodes;
    public boolean verbose;
    private TranspositionTable tt;
    private Book book;
    private boolean bookEnabled;
    private boolean randomMode;
    private Search currentSearch;

    public ComputerPlayer() {
        minTimeMillis = 10000;
        maxTimeMillis = 10000;
        maxDepth = 100;
        maxNodes = -1;
        verbose = true;
        // Android used 15 (32768 entries, ~1MB) - far too big for this
        // device's RAM. Starting conservative at ~34KB; bump this up once
        // testing on the real device/emulator shows how much headroom
        // there actually is (see PORTING_NOTES.md).
        setTTLogSize(10);
        book = new Book(verbose);
        bookEnabled = true;
        randomMode = false;
    }

    public void setTTLogSize(int logSize) {
        tt = new TranspositionTable(logSize);
    }
    
    private Search.Listener listener;
    public void setListener(Search.Listener listener) {
        this.listener = listener;
    }

    public String getCommand(Position pos, boolean drawOffer, Vector history) {
        // Create a search object
        long[] posHashList = new long[200 + history.size()];
        int posHashListSize = 0;
        for (int hi = 0; hi < history.size(); hi++) {
            Position p = (Position) history.elementAt(hi);
            posHashList[posHashListSize++] = p.zobristHash();
        }
        tt.nextGeneration();
        History ht = new History();
        Search sc = new Search(pos, posHashList, posHashListSize, tt, ht);

        // Determine all legal moves
        MoveGen.MoveList moves = new MoveGen().pseudoLegalMoves(pos);
        MoveGen.removeIllegal(pos, moves);
        sc.scoreMoveList(moves, 0);

        // Test for "game over"
        if (moves.size == 0) {
            // Switch sides so that the human can decide what to do next.
            return "swap";
        }

        if (bookEnabled) {
            Move bookMove = book.getBookMove(pos);
            if (bookMove != null) {
                System.out.println("Book moves: " + book.getAllBookMoves(pos));
                return TextIO.moveToString(pos, bookMove, false);
            }
        }
        
        // Find best move using iterative deepening
        currentSearch = sc;
        sc.setListener(listener);
        Move bestM;
        if ((moves.size == 1) && canClaimDraw(pos, posHashList, posHashListSize, moves.m[0]).length() == 0) {
            bestM = moves.m[0];
            bestM.score = 0;
        } else if (randomMode) {
            bestM = findSemiRandomMove(sc, moves);
        } else {
            sc.timeLimit(minTimeMillis, maxTimeMillis);
            bestM = sc.iterativeDeepening(moves, maxDepth, maxNodes, verbose);
        }
        currentSearch = null;
//        tt.printStats();
        String strMove = TextIO.moveToString(pos, bestM, false);

        // Claim draw if appropriate
        if (bestM.score <= 0) {
            String drawClaim = canClaimDraw(pos, posHashList, posHashListSize, bestM);
            if (drawClaim.length() != 0)
                strMove = drawClaim;
        }
        return strMove;
    }
    
    /** Check if a draw claim is allowed, possibly after playing "move".
     * @param move The move that may have to be made before claiming draw.
     * @return The draw string that claims the draw, or empty string if draw claim not valid.
     */
    private String canClaimDraw(Position pos, long[] posHashList, int posHashListSize, Move move) {
        String drawStr = "";
        if (Search.canClaimDraw50(pos)) {
            drawStr = "draw 50";
        } else if (Search.canClaimDrawRep(pos, posHashList, posHashListSize, posHashListSize)) {
            drawStr = "draw rep";
        } else {
            String strMove = TextIO.moveToString(pos, move, false);
            posHashList[posHashListSize++] = pos.zobristHash();
            UndoInfo ui = new UndoInfo();
            pos.makeMove(move, ui);
            if (Search.canClaimDraw50(pos)) {
                drawStr = "draw 50 " + strMove;
            } else if (Search.canClaimDrawRep(pos, posHashList, posHashListSize, posHashListSize)) {
                drawStr = "draw rep " + strMove;
            }
            pos.unMakeMove(move, ui);
        }
        return drawStr;
    }

    public boolean isHumanPlayer() {
        return false;
    }

    public void useBook(boolean bookOn) {
        bookEnabled = bookOn;
    }

    public void timeLimit(int minTimeLimit, int maxTimeLimit, boolean randomMode) {
        if (randomMode) {
            minTimeLimit = 0;
            maxTimeLimit = 0;
        }
        minTimeMillis = minTimeLimit;
        maxTimeMillis = maxTimeLimit;
        this.randomMode = randomMode;
        if (currentSearch != null) {
            currentSearch.timeLimit(minTimeLimit, maxTimeLimit);
        }
    }

    public void clearTT() {
        tt.clear();
    }

    // searchPosition() was intentionally dropped here - see the file-header
    // comment. It returned a generic TwoReturnValues<Move,String> for
    // CuckooChess's own EPD test-suite runner, which isn't part of this
    // port; getCommand() above (what our MIDlet UI actually calls to get a
    // move) never used it.

    private Move findSemiRandomMove(Search sc, MoveGen.MoveList moves) {
        sc.timeLimit(minTimeMillis, maxTimeMillis);
        Move bestM = sc.iterativeDeepening(moves, 1, maxNodes, verbose);
        int bestScore = bestM.score;

        Random rndGen = new Random(System.currentTimeMillis());

        int sum = 0;
        for (int mi = 0; mi < moves.size; mi++) {
            sum += moveProbWeight(moves.m[mi].score, bestScore);
        }
        int rnd = rndGen.nextInt(sum);
        for (int mi = 0; mi < moves.size; mi++) {
            int weight = moveProbWeight(moves.m[mi].score, bestScore);
            if (rnd < weight) {
                return moves.m[mi];
            }
            rnd -= weight;
        }
        // Unreachable if sum/rnd above are correct - using an exception
        // instead of "assert" since CLDC toolchains can't be relied on to
        // support the assert keyword's classfile attribute.
        throw new RuntimeException("findSemiRandomMove: no move selected");
    }

    // Precomputed replacement for Math.exp(): CLDC 1.1's Math class has
    // sqrt/abs/ceil/floor but no exp/log/sin/cos/pow (no transcendental
    // functions at all). GAUSS_WEIGHT[i] holds exactly what the original
    // formula - 100*exp(-(i/100.0)^2/2), rounded up - produces for a score
    // gap of i centipawns, so "weak mode" move selection is unaffected.
    // Beyond the table's range the true value is already 1 (a huge score
    // gap should get essentially no chance of being picked), so anything
    // past the end just clamps to 1 instead of growing the table further.
    private static final int[] GAUSS_WEIGHT = {
        100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 99, 99, 99, 99, 99,
        99, 98, 98, 98, 98, 97, 97, 97, 97, 96, 96, 96, 96, 95, 95, 95, 94, 94, 94, 93,
        93, 92, 92, 92, 91, 91, 90, 90, 90, 89, 89, 88, 88, 87, 87, 86, 86, 86, 85, 85,
        84, 84, 83, 83, 82, 81, 81, 80, 80, 79, 79, 78, 78, 77, 77, 76, 75, 75, 74, 74,
        73, 73, 72, 71, 71, 70, 70, 69, 68, 68, 67, 67, 66, 65, 65, 64, 64, 63, 62, 62,
        61, 61, 60, 59, 59, 58, 58, 57, 56, 56, 55, 55, 54, 53, 53, 52, 52, 51, 50, 50,
        49, 49, 48, 47, 47, 46, 46, 45, 45, 44, 43, 43, 42, 42, 41, 41, 40, 40, 39, 39,
        38, 38, 37, 36, 36, 35, 35, 34, 34, 33, 33, 32, 32, 32, 31, 31, 30, 30, 29, 29,
        28, 28, 27, 27, 27, 26, 26, 25, 25, 24, 24, 24, 23, 23, 23, 22, 22, 21, 21, 21,
        20, 20, 20, 19, 19, 19, 18, 18, 18, 17, 17, 17, 16, 16, 16, 15, 15, 15, 15, 14,
        14, 14, 14, 13, 13, 13, 12, 12, 12, 12, 12, 11, 11, 11, 11, 10, 10, 10, 10, 10,
        9, 9, 9, 9, 9, 8, 8, 8, 8, 8, 8, 7, 7, 7, 7, 7, 7, 7, 6, 6,
        6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4,
        4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 1, 1, 1, 1, 1, 1
    };

    private static int moveProbWeight(int moveScore, int bestScore) {
        int diff = bestScore - moveScore;
        if (diff < 0) diff = 0; // bestScore should always be the max, but never return <=0 weight
        if (diff >= GAUSS_WEIGHT.length) return 1;
        return GAUSS_WEIGHT[diff];
    }
}

