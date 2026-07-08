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

// Adapted for J2ME/CLDC 1.1 (it5260 build). Changes in this file:
//  - `public enum GameState` -> plain `public static final int` constants
//    directly on Game (first enum in this codebase; CLDC has no enum
//    keyword at all). Nothing outside this file referenced GameState, so
//    they're flattened straight onto Game rather than kept as a nested
//    namespace - external code now uses e.g. Game.ALIVE. The switch
//    statements that used bare `case ALIVE:` labels still work unchanged,
//    since these are now simple members of the enclosing class.
//  - List<Move>/List<UndoInfo>/List<Boolean> -> Vector, using CLDC's actual
//    method names (addElement/elementAt/removeElementAt/setElementAt), with
//    manual Boolean boxing (no autoboxing in CLDC) for drawOfferList.
//  - Collections.reverse() doesn't exist in CLDC -> manual in-place reverse.
//  - Several String.format(Locale.US, "%-10s"/"%3d"/"%n", ...) calls needed
//    real column padding (this feeds the move-list display), so this file
//    gets small padLeft()/padRight() helpers rather than dropping alignment.
//  - printf -> println/print + concatenation elsewhere.
//  - Long.bitCount() (Java 5+, not in CLDC) -> BitBoard.bitCount(), the
//    hand-written popcount added there while porting this file.
package chess;

import java.util.Vector;

public class Game {
    protected Vector moveList = null;      // of Move
    protected Vector uiInfoList = null;    // of UndoInfo
    private Vector drawOfferList = null;   // of Boolean
    protected int currentMove;
    boolean pendingDrawOffer;
    int drawState;
    private String drawStateMoveStr; // Move required to claim DRAW_REP or DRAW_50
    private int resignState;
    public Position pos = null;
    protected Player whitePlayer;
    protected Player blackPlayer;

    public static final int ALIVE = 0;
    public static final int WHITE_MATE = 1;         // White mates
    public static final int BLACK_MATE = 2;         // Black mates
    public static final int WHITE_STALEMATE = 3;    // White is stalemated
    public static final int BLACK_STALEMATE = 4;    // Black is stalemated
    public static final int DRAW_REP = 5;           // Draw by 3-fold repetition
    public static final int DRAW_50 = 6;            // Draw by 50 move rule
    public static final int DRAW_NO_MATE = 7;       // Draw by impossibility of check mate
    public static final int DRAW_AGREE = 8;         // Draw by agreement
    public static final int RESIGN_WHITE = 9;       // White resigns
    public static final int RESIGN_BLACK = 10;      // Black resigns

    public Game(Player whitePlayer, Player blackPlayer) {
        this.whitePlayer = whitePlayer;
        this.blackPlayer = blackPlayer;
        handleCommand("new");
    }

    /**
     * Update the game state according to move/command string from a player.
     * @param str The move or command to process.
     * @return True if str was understood, false otherwise.
     */
    public boolean processString(String str) {
        if (handleCommand(str)) {
            return true;
        }
        if (getGameState() != ALIVE) {
            return false;
        }

        Move m = TextIO.stringToMove(pos, str);
        if (m == null) {
            return false;
        }

        UndoInfo ui = new UndoInfo();
        pos.makeMove(m, ui);
        TextIO.fixupEPSquare(pos);
        while (currentMove < moveList.size()) {
            moveList.removeElementAt(currentMove);
            uiInfoList.removeElementAt(currentMove);
            drawOfferList.removeElementAt(currentMove);
        }
        moveList.addElement(m);
        uiInfoList.addElement(ui);
        drawOfferList.addElement(new Boolean(pendingDrawOffer));
        pendingDrawOffer = false;
        currentMove++;
        return true;
    }

    public final String getGameStateString() {
        switch (getGameState()) {
            case ALIVE:
                return "";
            case WHITE_MATE:
                return "Game over, white mates!";
            case BLACK_MATE:
                return "Game over, black mates!";
            case WHITE_STALEMATE:
            case BLACK_STALEMATE:
                return "Game over, draw by stalemate!";
            case DRAW_REP:
            {
                String ret = "Game over, draw by repetition!";
                if ((drawStateMoveStr != null) && (drawStateMoveStr.length() > 0)) {
                    ret = ret + " [" + drawStateMoveStr + "]";
                }
                return ret;
            }
            case DRAW_50:
            {
                String ret = "Game over, draw by 50 move rule!";
                if ((drawStateMoveStr != null) && (drawStateMoveStr.length() > 0)) {
                    ret = ret + " [" + drawStateMoveStr + "]";  
                }
                return ret;
            }
            case DRAW_NO_MATE:
                return "Game over, draw by impossibility of mate!";
            case DRAW_AGREE:
                return "Game over, draw by agreement!";
            case RESIGN_WHITE:
                return "Game over, white resigns!";
            case RESIGN_BLACK:
                return "Game over, black resigns!";
            default:
                throw new RuntimeException();
        }
    }

    /**
     * Get the last played move, or null if no moves played yet.
     */
    public Move getLastMove() {
        Move m = null;
        if (currentMove > 0) {
            m = (Move) moveList.elementAt(currentMove - 1);
        }
        return m;
    }

    /**
     * Get the current state of the game.
     */
    public int getGameState() {
        MoveGen.MoveList moves = new MoveGen().pseudoLegalMoves(pos);
        MoveGen.removeIllegal(pos, moves);
        if (moves.size == 0) {
            if (MoveGen.inCheck(pos)) {
                return pos.whiteMove ? BLACK_MATE : WHITE_MATE;
            } else {
                return pos.whiteMove ? WHITE_STALEMATE : BLACK_STALEMATE;
            }
        }
        if (insufficientMaterial()) {
            return DRAW_NO_MATE;
        }
        if (resignState != ALIVE) {
            return resignState;
        }
        return drawState;
    }

    /**
     * Check if a draw offer is available.
     * @return True if the current player has the option to accept a draw offer.
     */
    public boolean haveDrawOffer() {
        if (currentMove > 0) {
            return ((Boolean) drawOfferList.elementAt(currentMove - 1)).booleanValue();
        } else {
            return false;
        }
    }
    
    /**
     * Handle a special command.
     * @param moveStr  The command to handle
     * @return  True if command handled, false otherwise.
     */
    protected boolean handleCommand(String moveStr) {
        if (moveStr.equals("new")) {
            moveList = new Vector();
            uiInfoList = new Vector();
            drawOfferList = new Vector();
            currentMove = 0;
            pendingDrawOffer = false;
            drawState = ALIVE;
            resignState = ALIVE;
            try {
                pos = TextIO.readFEN(TextIO.startPosFEN);
            } catch (ChessParseError ex) {
                throw new RuntimeException();
            }
            whitePlayer.clearTT();
            blackPlayer.clearTT();
            activateHumanPlayer();
            return true;
        } else if (moveStr.equals("undo")) {
            if (currentMove > 0) {
                pos.unMakeMove((Move) moveList.elementAt(currentMove - 1),
                               (UndoInfo) uiInfoList.elementAt(currentMove - 1));
                currentMove--;
                pendingDrawOffer = false;
                drawState = ALIVE;
                resignState = ALIVE;
                return handleCommand("swap");
            } else {
                System.out.println("Nothing to undo");
            }
            return true;
        } else if (moveStr.equals("redo")) {
            if (currentMove < moveList.size()) {
                pos.makeMove((Move) moveList.elementAt(currentMove),
                             (UndoInfo) uiInfoList.elementAt(currentMove));
                currentMove++;
                pendingDrawOffer = false;
                return handleCommand("swap");
            } else {
                System.out.println("Nothing to redo");
            }
            return true;
        } else if (moveStr.equals("swap") || moveStr.equals("go")) {
            Player tmp = whitePlayer;
            whitePlayer = blackPlayer;
            blackPlayer = tmp;
            return true;
        } else if (moveStr.equals("list")) {
            listMoves();
            return true;
        } else if (moveStr.startsWith("setpos ")) {
            String fen = moveStr.substring(moveStr.indexOf(" ") + 1);
            Position newPos = null;
            try {
                newPos = TextIO.readFEN(fen);
            } catch (ChessParseError ex) {
                System.out.println("Invalid FEN: " + fen + " (" + ex.getMessage() + ")");
            }
            if (newPos != null) {
                handleCommand("new");
                pos = newPos;
                activateHumanPlayer();
            }
            return true;
        } else if (moveStr.equals("getpos")) {
            String fen = TextIO.toFEN(pos);
            System.out.println(fen);
            return true;
        } else if (moveStr.startsWith("draw ")) {
            if (getGameState() == ALIVE) {
                String drawCmd = moveStr.substring(moveStr.indexOf(" ") + 1);
                return handleDrawCmd(drawCmd);
            } else {
                return true;
            }
        } else if (moveStr.equals("resign")) {
            if (getGameState()== ALIVE) {
                resignState = pos.whiteMove ? RESIGN_WHITE : RESIGN_BLACK;
                return true;
            } else {
                return true;
            }
        } else if (moveStr.startsWith("book")) {
            String bookCmd = moveStr.substring(moveStr.indexOf(" ") + 1);
            return handleBookCmd(bookCmd);
        } else if (moveStr.startsWith("time")) {
            try {
                String timeStr = moveStr.substring(moveStr.indexOf(" ") + 1);
                int timeLimit = Integer.parseInt(timeStr);
                whitePlayer.timeLimit(timeLimit, timeLimit, false);
                blackPlayer.timeLimit(timeLimit, timeLimit, false);
                return true;
            }
            catch (NumberFormatException nfe) {
                System.out.println("Number format exception: " + nfe.getMessage());
                return false;
            }
        } else if (moveStr.startsWith("perft ")) {
            try {
                String depthStr = moveStr.substring(moveStr.indexOf(" ") + 1);
                int depth = Integer.parseInt(depthStr);
                MoveGen moveGen = new MoveGen();
                long t0 = System.currentTimeMillis();
                long nodes = perfT(moveGen, pos, depth);
                long t1 = System.currentTimeMillis();
                System.out.println("perft(" + depth + ") = " + nodes + ", t=" +
                        ((t1 - t0)*1e-3) + "s");
            }
            catch (NumberFormatException nfe) {
                System.out.println("Number format exception: " + nfe.getMessage());
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    /** Swap players around if needed to make the human player in control of the next move. */
    protected void activateHumanPlayer() {
        if (!(pos.whiteMove ? whitePlayer : blackPlayer).isHumanPlayer()) {
            Player tmp = whitePlayer;
            whitePlayer = blackPlayer;
            blackPlayer = tmp;
        }
    }

    public Vector getPosHistory() {
        Vector ret = new Vector();
        
        Position pos = new Position(this.pos);
        for (int i = currentMove; i > 0; i--) {
            pos.unMakeMove((Move) moveList.elementAt(i - 1), (UndoInfo) uiInfoList.elementAt(i - 1));
        }
        ret.addElement(TextIO.toFEN(pos)); // Store initial FEN

        StringBuffer moves = new StringBuffer();
        for (int i = 0; i < moveList.size(); i++) {
            Move move = (Move) moveList.elementAt(i);
            String strMove = TextIO.moveToString(pos, move, false);
            moves.append(" ");
            moves.append(strMove);
            UndoInfo ui = new UndoInfo();
            pos.makeMove(move, ui);
        }
        ret.addElement(moves.toString()); // Store move list string
        int numUndo = moveList.size() - currentMove;
        ret.addElement(String.valueOf(numUndo));
        return ret;
    }

    /**
     * Print a list of all moves.
     */
    private void listMoves() {
        String movesStr = getMoveListString(false);
        System.out.print(movesStr);
    }

    // Small manual replacements for String.format's %-Ns / %Nd column
    // padding, since CLDC has no Formatter at all.
    private static String padRight(String s, int width) {
        StringBuffer sb = new StringBuffer(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
    private static String padLeft(String s, int width) {
        StringBuffer sb = new StringBuffer();
        for (int i = s.length(); i < width; i++) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    final public String getMoveListString(boolean compressed) {
        StringBuffer ret = new StringBuffer();

        // Undo all moves in move history.
        Position pos = new Position(this.pos);
        for (int i = currentMove; i > 0; i--) {
            pos.unMakeMove((Move) moveList.elementAt(i - 1), (UndoInfo) uiInfoList.elementAt(i - 1));
        }

        // Print all moves
        String whiteMove = "";
        String blackMove = "";
        for (int i = 0; i < currentMove; i++) {
            Move move = (Move) moveList.elementAt(i);
            String strMove = TextIO.moveToString(pos, move, false);
            if (((Boolean) drawOfferList.elementAt(i)).booleanValue()) {
                strMove += " (d)";
            }
            if (pos.whiteMove) {
                whiteMove = strMove;
            } else {
                blackMove = strMove;
                if (whiteMove.length() == 0) {
                    whiteMove = "...";
                }
                if (compressed) {
                    ret.append(pos.fullMoveCounter);
                    ret.append(". ");
                    ret.append(whiteMove);
                    ret.append(" ");
                    ret.append(blackMove);
                    ret.append(" ");
                } else {
                    ret.append(padLeft(String.valueOf(pos.fullMoveCounter), 3));
                    ret.append(".  ");
                    ret.append(padRight(whiteMove, 10));
                    ret.append(" ");
                    ret.append(padRight(blackMove, 10));
                    ret.append("\n");
                }
                whiteMove = "";
                blackMove = "";
            }
            UndoInfo ui = new UndoInfo();
            pos.makeMove(move, ui);
        }
        if (whiteMove.length() > 0) {
            if (compressed) {
                ret.append(pos.fullMoveCounter);
                ret.append(". ");
                ret.append(whiteMove);
                ret.append(" ");
                ret.append(blackMove);
                ret.append(" ");
            } else {
                ret.append(padLeft(String.valueOf(pos.fullMoveCounter), 3));
                ret.append(".  ");
                ret.append(padRight(whiteMove, 8));
                ret.append(" ");
                ret.append(padRight(blackMove, 8));
                ret.append("\n");
            }
        }
        String gameResult = getPGNResultString();
        if (!gameResult.equals("*")) {
            if (compressed) {
                ret.append(gameResult);
            } else {
                ret.append(gameResult);
                ret.append("\n");
            }
        }
        return ret.toString();
    }
    
    public final String getPGNResultString() {
        String gameResult = "*";
        switch (getGameState()) {
            case ALIVE:
                break;
            case WHITE_MATE:
            case RESIGN_BLACK:
                gameResult = "1-0";
                break;
            case BLACK_MATE:
            case RESIGN_WHITE:
                gameResult = "0-1";
                break;
            case WHITE_STALEMATE:
            case BLACK_STALEMATE:
            case DRAW_REP:
            case DRAW_50:
            case DRAW_NO_MATE:
            case DRAW_AGREE:
                gameResult = "1/2-1/2";
                break;
        }
        return gameResult;
    }

    /** Return a list of previous positions in this game, back to the last "zeroing" move. */
    public Vector getHistory() {
        Vector posList = new Vector();
        Position pos = new Position(this.pos);
        for (int i = currentMove; i > 0; i--) {
            if (pos.halfMoveClock == 0)
                break;
            pos.unMakeMove((Move) moveList.elementAt(i - 1), (UndoInfo) uiInfoList.elementAt(i - 1));
            posList.addElement(new Position(pos));
        }
        // Collections.reverse() isn't in CLDC - manual in-place reverse.
        for (int i = 0, j = posList.size() - 1; i < j; i++, j--) {
            Object tmp = posList.elementAt(i);
            posList.setElementAt(posList.elementAt(j), i);
            posList.setElementAt(tmp, j);
        }
        return posList;
    }

    private boolean handleDrawCmd(String drawCmd) {
        if (drawCmd.startsWith("rep") || drawCmd.startsWith("50")) {
            boolean rep = drawCmd.startsWith("rep");
            Move m = null;
            String ms = drawCmd.substring(drawCmd.indexOf(" ") + 1);
            if (ms.length() > 0) {
                m = TextIO.stringToMove(pos, ms);
            }
            boolean valid;
            if (rep) {
                valid = false;
                Vector oldPositions = new Vector();
                if (m != null) {
                    UndoInfo ui = new UndoInfo();
                    Position tmpPos = new Position(pos);
                    tmpPos.makeMove(m, ui);
                    oldPositions.addElement(tmpPos);
                }
                oldPositions.addElement(pos);
                Position tmpPos = pos;
                for (int i = currentMove - 1; i >= 0; i--) {
                    tmpPos = new Position(tmpPos);
                    tmpPos.unMakeMove((Move) moveList.elementAt(i), (UndoInfo) uiInfoList.elementAt(i));
                    oldPositions.addElement(tmpPos);
                }
                int repetitions = 0;
                Position firstPos = (Position) oldPositions.elementAt(0);
                for (int i = 0; i < oldPositions.size(); i++) {
                    Position p = (Position) oldPositions.elementAt(i);
                    if (p.drawRuleEquals(firstPos))
                        repetitions++;
                }
                if (repetitions >= 3) {
                    valid = true;
                }
            } else {
                Position tmpPos = new Position(pos);
                if (m != null) {
                    UndoInfo ui = new UndoInfo();
                    tmpPos.makeMove(m, ui);
                }
                valid = tmpPos.halfMoveClock >= 100;
            }
            if (valid) {
                drawState = rep ? DRAW_REP : DRAW_50;
                drawStateMoveStr = null;
                if (m != null) {
                    drawStateMoveStr = TextIO.moveToString(pos, m, false);
                }
            } else {
                pendingDrawOffer = true;
                if (m != null) {
                    processString(ms);
                }
            }
            return true;
        } else if (drawCmd.startsWith("offer ")) {
            pendingDrawOffer = true;
            String ms = drawCmd.substring(drawCmd.indexOf(" ") + 1);
            if (TextIO.stringToMove(pos, ms) != null) {
                processString(ms);
            }
            return true;
        } else if (drawCmd.equals("accept")) {
            if (haveDrawOffer()) {
                drawState = DRAW_AGREE;
            }
            return true;
        } else {
            return false;
        }
    }

    private boolean handleBookCmd(String bookCmd) {
        if (bookCmd.equals("off")) {
            whitePlayer.useBook(false);
            blackPlayer.useBook(false);
            return true;
        } else if (bookCmd.equals("on")) {
            whitePlayer.useBook(true);
            whitePlayer.useBook(true);
            return true;
        }
        return false;
    }

    private boolean insufficientMaterial() {
        if (pos.pieceTypeBB[Piece.WQUEEN] != 0) return false;
        if (pos.pieceTypeBB[Piece.WROOK]  != 0) return false;
        if (pos.pieceTypeBB[Piece.WPAWN]  != 0) return false;
        if (pos.pieceTypeBB[Piece.BQUEEN] != 0) return false;
        if (pos.pieceTypeBB[Piece.BROOK]  != 0) return false;
        if (pos.pieceTypeBB[Piece.BPAWN]  != 0) return false;
        int wb = BitBoard.bitCount(pos.pieceTypeBB[Piece.WBISHOP]);
        int wn = BitBoard.bitCount(pos.pieceTypeBB[Piece.WKNIGHT]);
        int bb = BitBoard.bitCount(pos.pieceTypeBB[Piece.BBISHOP]);
        int bn = BitBoard.bitCount(pos.pieceTypeBB[Piece.BKNIGHT]);
        if (wb + wn + bb + bn <= 1) {
            return true;    // King + bishop/knight vs king is draw
        }
        if (wn + bn == 0) {
            // Only bishops. If they are all on the same color, the position is a draw.
            long bMask = pos.pieceTypeBB[Piece.WBISHOP] | pos.pieceTypeBB[Piece.BBISHOP];
            if (((bMask & BitBoard.maskDarkSq) == 0) ||
                ((bMask & BitBoard.maskLightSq) == 0))
                return true;
        }

        return false;
    }

    static long perfT(MoveGen moveGen, Position pos, int depth) {
        if (depth == 0)
            return 1;
        long nodes = 0;
        MoveGen.MoveList moves = moveGen.pseudoLegalMoves(pos);
        MoveGen.removeIllegal(pos, moves);
        if (depth == 1) {
            int ret = moves.size;
            moveGen.returnMoveList(moves);
            return ret;
        }
        UndoInfo ui = new UndoInfo();
        for (int mi = 0; mi < moves.size; mi++) {
            Move m = moves.m[mi];
            pos.makeMove(m, ui);
            nodes += perfT(moveGen, pos, depth - 1);
            pos.unMakeMove(m, ui);
        }
        moveGen.returnMoveList(moves);
        return nodes;
    }
}
