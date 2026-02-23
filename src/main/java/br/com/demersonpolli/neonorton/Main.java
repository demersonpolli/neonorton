package br.com.demersonpolli.neonorton;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import com.googlecode.lanterna.terminal.swing.TerminalEmulatorAutoCloseTrigger;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            SwingTerminalFrame terminal = new SwingTerminalFrame(
                    "NeoNorton",
                    TerminalEmulatorAutoCloseTrigger.CloseOnExitPrivateMode);
            terminal.setVisible(true);

            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();

            MultiWindowTextGUI gui = new MultiWindowTextGUI(
                    screen,
                    new DefaultWindowManager(),
                    new EmptySpace(TextColor.ANSI.BLUE));

            SplashScreen splash = new SplashScreen();
            splash.show(gui);
            String fileName = splash.getFileName();

            new EditorScreen(fileName).show(gui);

            screen.stopScreen();
            terminal.dispose();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}