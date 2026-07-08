package chess;

import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

/**
 * MIDlet lifecycle entry point. Deliberately minimal for now - just enough
 * to make ChessCanvas launchable and testable (e.g. in J2ME-Loader). No
 * menu Commands yet (new game/undo/save/difficulty), and no pause/resume
 * state handling beyond what MIDP does automatically - those land with the
 * rest of Phase 4.
 */
public class ChessMIDlet extends MIDlet {
    private ChessCanvas canvas;

    protected void startApp() {
        if (canvas == null) {
            canvas = new ChessCanvas();
        }
        Display.getDisplay(this).setCurrent(canvas);
    }

    protected void pauseApp() {
        // Nothing to release - the board and game state live in `canvas`
        // and just stay as they are.
    }

    protected void destroyApp(boolean unconditional) {
    }
}
