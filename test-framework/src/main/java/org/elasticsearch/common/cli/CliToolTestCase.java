/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.cli;

import org.apache.commons.cli.CommandLine;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.Strings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.StreamsUtils;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.unmodifiableMap;
import static org.elasticsearch.common.cli.CliToolConfig.Builder.cmd;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

public abstract class CliToolTestCase extends ESTestCase {

    @Before
    public void setPathHome() {
        System.setProperty("es.default.path.home", createTempDir().toString());
    }

    @After
    public void clearPathHome() {
        System.clearProperty("es.default.path.home");
    }

    public static String[] args(String command) {
        if (!Strings.hasLength(command)) {
            return Strings.EMPTY_ARRAY;
        }
        return command.split("\\s+");
    }
    

    public static void assertStatus(CliTool.ExitStatus status, CliTool.ExitStatus expectedStatus) {
        assertThat(status, is(expectedStatus));
    }

    public static void assertCommandHasBeenExecuted(AtomicReference<Boolean> executed) {
        assertThat("Expected command atomic reference counter to be set to true", executed.get(), is(Boolean.TRUE));
    }

    /**
     * A terminal implementation that discards everything
     */
    public static class MockTerminal extends Terminal {

        private static final PrintWriter DEV_NULL = new PrintWriter(new DevNullWriter());

        public MockTerminal() {
            super(Verbosity.NORMAL);
        }

        public MockTerminal(Verbosity verbosity) {
            super(verbosity);
        }

        @Override
        protected void doPrint(String msg, Object... args) {
        }

        @Override
        public String readText(String text, Object... args) {
            return null;
        }

        @Override
        public char[] readSecret(String text, Object... args) {
            return new char[0];
        }

        @Override
        public void print(String msg, Object... args) {
        }

        @Override
        public void printStackTrace(Throwable t) {
            return;
        }

        @Override
        public PrintWriter writer() {
            return DEV_NULL;
        }

        private static class DevNullWriter extends Writer {

            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
            }

            @Override
            public void flush() throws IOException {
            }

            @Override
            public void close() throws IOException {
            }
        }
    }

    /**
     * A terminal implementation that captures everything written to it
     */
    public static class CaptureOutputTerminal extends MockTerminal {

        List<String> terminalOutput = new ArrayList<>();

        public CaptureOutputTerminal() {
            super(Verbosity.NORMAL);
        }

        public CaptureOutputTerminal(Verbosity verbosity) {
            super(verbosity);
        }

        @Override
        protected void doPrint(String msg, Object... args) {
            terminalOutput.add(String.format(Locale.ROOT, msg, args));
        }

        @Override
        public void print(String msg, Object... args) {
            doPrint(msg, args);
        }

        @Override
        public void printStackTrace(Throwable t) {
            terminalOutput.add(ExceptionsHelper.stackTrace(t));
        }

        public List<String> getTerminalOutput() {
            return terminalOutput;
        }
    }

    public static void assertTerminalOutputContainsHelpFile(CliToolTestCase.CaptureOutputTerminal terminal, String classPath) throws IOException {
        List<String> nonEmptyLines = new ArrayList<>();
        for (String line : terminal.getTerminalOutput()) {
            String originalPrintedLine = line.replaceAll(System.lineSeparator(), "");
            if (Strings.isNullOrEmpty(originalPrintedLine)) {
                nonEmptyLines.add(originalPrintedLine);
            }
        }
        assertThat(nonEmptyLines, hasSize(greaterThan(0)));

        String expectedDocs = StreamsUtils.copyToStringFromClasspath(classPath);
        for (String nonEmptyLine : nonEmptyLines) {
            assertThat(expectedDocs, containsString(nonEmptyLine.replaceAll(System.lineSeparator(), "")));
        }
    }
    

    public static abstract class NamedCommand extends CliTool.Command {

        public final String name;

        public NamedCommand(String name, Terminal terminal) {
            super(terminal);
            this.name = name;
        }
    }
    

    public static class SingleCmdTool extends CliTool {

        public final Command command;

        public SingleCmdTool(String name, Terminal terminal, NamedCommand command) {
            super(CliToolConfig.config(name, SingleCmdTool.class)
                    .cmds(cmd(command.name, command.getClass()))
                    .build(), terminal);
            this.command = command;
        }

        @Override
        protected Command parse(String cmdName, CommandLine cli) throws Exception {
            return command;
        }
    }

    public static class MultiCmdTool extends CliTool {

        public final Map<String, Command> commands;

        public MultiCmdTool(String name, Terminal terminal, NamedCommand... commands) {
            super(CliToolConfig.config(name, MultiCmdTool.class)
                    .cmds(cmds(commands))
                    .build(), terminal);
            Map<String, Command> commandByName = new HashMap<>();
            for (int i = 0; i < commands.length; i++) {
                commandByName.put(commands[i].name, commands[i]);
            }
            this.commands = unmodifiableMap(commandByName);
        }

        @Override
        protected Command parse(String cmdName, CommandLine cli) throws Exception {
            return commands.get(cmdName);
        }

        private static CliToolConfig.Cmd[] cmds(NamedCommand... commands) {
            CliToolConfig.Cmd[] cmds = new CliToolConfig.Cmd[commands.length];
            for (int i = 0; i < commands.length; i++) {
                cmds[i] = cmd(commands[i].name, commands[i].getClass()).build();
            }
            return cmds;
        }
    }
}
