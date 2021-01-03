/*
 * The MIT License
 *
 * Copyright (c) 2011 Daniel Doubrovkine
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ansicolor;

import hudson.console.ConsoleNote;
import hudson.util.NullStream;
import org.fusesource.jansi.AnsiColors;
import org.fusesource.jansi.AnsiMode;
import org.fusesource.jansi.AnsiType;
import org.fusesource.jansi.io.AnsiOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Filters an output stream of ANSI escape sequences and emits appropriate HTML elements instead.
 * <p>
 * Overlapping ANSI attribute combinations are handled by rewinding the HTML element stack.
 * <p>
 * How the HTML is actually emitted depends on the specified {@link AnsiAttributeElement.Emitter}. For Jenkins, the Emitter creates {@link ConsoleNote}s as part of the stream, but for other software
 * or testing the HTML may be emitted otherwise.
 * <p>
 * The only thing that ties this class to Jenkins is the rather unfortunate {@link ConsoleNote} preamble/postamble handling via state machine. Simply remove this if you plan to use this class
 * somewhere else.
 */
public class AnsiHtmlOutputStream extends AnsiOutputStream {
    private final AnsiColorMap colorMap;
    private final AnsiAttributeElement.Emitter emitter;

    private enum State {
        INIT, DATA, PREAMBLE, NOTE, POSTAMBLE
    }

    private State state = State.INIT;
    private int amblePos = 0;

    private String currentForegroundColor = null;
    private String currentBackgroundColor = null;
    private boolean swapColors = false;  // true if negative / inverse mode is active (esc[7m)

    // A Deque might be a better choice, but we are constrained by the Java 5 API.
    private final ArrayList<AnsiAttributeElement> openTags;

    private final OutputStream logOutput;

    private final AnsiToHtmlProcessor processor;

    /**
     * @param tagsToOpen A list of tags to open in the given order immediately after opening the tag for the default foreground/background colors (if such colors are specified by the color map) before
     * any data is written to the underlying stream.
     */
    AnsiHtmlOutputStream(
        final OutputStream os,
        final AnsiToHtmlProcessor processor,
        final AnsiColorMap colorMap,
        final AnsiAttributeElement.Emitter emitter,
        @Nonnull List<AnsiAttributeElement> tagsToOpen
    ) {
        super(os, AnsiMode.Default, processor, AnsiType.Emulation, AnsiColors.TrueColor, StandardCharsets.UTF_8, null, null, false);
        this.logOutput = os;
        this.colorMap = colorMap;
        this.emitter = emitter;
        this.openTags = new ArrayList<>(tagsToOpen);
        this.processor = processor;
    }

    public AnsiHtmlOutputStream(final OutputStream os, final AnsiToHtmlProcessor processor, final AnsiColorMap colorMap, final AnsiAttributeElement.Emitter emitter) {
        this(os, processor, colorMap, emitter, Collections.emptyList());
    }

    // Debug output for plugin developers. Puts the debug message into the html page
    @SuppressWarnings("unused")
    private void logdebug(String format, Object... args) throws IOException {
        String msg = String.format(format, args);
        emitter.emitHtml("<span style=\"border: 1px solid; color: #009000; background-color: #003000; font-size: 70%; font-weight: normal; font-style: normal\">");
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        emitter.emitHtml("</span>");
    }

    /**
     * @return A copy of the {@link AnsiAttributeElement}s which are currently opened, in order from outermost to innermost tag.
     */
    List<AnsiAttributeElement> getOpenTags() {
        return new ArrayList<>(openTags);
    }


    @Override
    public void write(int data) throws IOException {
        if (state == State.INIT) {
            processor.initTags();
            state = State.DATA;
        }

        switch (state) {
            case DATA:
                if (data == ConsoleNote.PREAMBLE[0]) {
                    state = State.PREAMBLE;
                    amblePos = 0;
                    collectAmbleCharacter(data, ConsoleNote.PREAMBLE);
                } else {
                    processAndwriteToOutputStream(data);
                }
                break;
            case NOTE:
                if (data == ConsoleNote.POSTAMBLE[0]) {
                    state = State.POSTAMBLE;
                    amblePos = 0;
                    collectAmbleCharacter(data, ConsoleNote.POSTAMBLE);
                } else {
                    processAndwriteToOutputStream(data);
                }
                break;
            case PREAMBLE:
                collectAmbleCharacter(data, ConsoleNote.PREAMBLE);
                break;
            case POSTAMBLE:
                collectAmbleCharacter(data, ConsoleNote.POSTAMBLE);
                break;
            default:
                throw new IllegalStateException("State " + state + " should not be reached");
        }
    }

    private void collectAmbleCharacter(int data, byte[] amble) throws IOException {
        // The word "amble" is a cute generalization of preamble and postamble.

        if (data != amble[amblePos]) {
            // This was not an amble after all, so replay everything and try interpreting it as ANSI.
            for (int i = 0; i < amblePos; i++) {
                processAndwriteToOutputStream(amble[i]);
            }

            // Replay the new character in the state machine (may or may not be part of another amble).
            state = state == State.POSTAMBLE ? State.NOTE : State.DATA;
            amblePos = 0;
            write(data);
        } else if (amblePos == amble.length - 1) {
            // Successfully read a whole preamble
            writeToOutputStream(amble);
            state = state == State.POSTAMBLE ? State.DATA : State.NOTE;
            amblePos = 0;
        } else {
            amblePos++;
        }
    }

    public static final OutputStream NULL_STREAM = new NullStream();

    private void processAndwriteToOutputStream(int b) throws IOException {
        out = processor.isWritingEnabled() ? logOutput : NULL_STREAM;
        super.write(b);
    }

    private void writeToOutputStream(byte[] b) throws IOException {
        out = processor.isWritingEnabled() ? logOutput : NULL_STREAM;
        out.write(b);
    }

    @Override
    public void close() throws IOException {
        processor.finish();
        super.close();
    }
}
