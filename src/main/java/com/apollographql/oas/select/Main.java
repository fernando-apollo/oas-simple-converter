package com.apollographql.oas.select;

import com.apollographql.oas.select.prompt.Prompt;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.*;
import java.util.concurrent.Callable;

@Command(name = "generate", mixinStandardHelpOptions = true, version = "generate 0.1",
  description = "Generates an Apollo Connector from an OAS/Swagger spec")
class VisitorCommand implements Callable<Integer> {
  @Parameters(paramLabel = "<source>>", description = "a Swagger/OAS file")
  File source;

  @Option(names = {"-o", "--output-file"}, paramLabel = "<file>", description = "the output file")
  File output;

  @Option(names = {"-i", "--input-type"}, paramLabel = "<prompt|record|skip>",
    description = """
the input type to use:
  - prompt: prompt for field selection whilst traversing the input schema,
  - record: prompt the user and record every every response, which will output the recording at end,
  - skip: do not prompt for field selection, only path selection  \s
""",
    defaultValue = "prompt", fallbackValue = "prompt")
  String inputType;

  @Option(names = {"-r", "--recording"}, paramLabel = "<file>",
    description = """
the recording to use - the format is a multi-line file with "y", "n", or "s" at the beginning of each line.
For path selection use "y" or "n", for field selection the prompt will first ask to select all fields. In
this case "s" becomes useful to select only a subset. Using this option will prompt for every field instead.
Last but not least, if specified, this option overrides '--input-type'.\s
""")
  File recording;

  @Option(names = {"-h", "--help"}, usageHelp = true, description = "display a help message")
  boolean helpRequested = false;


  @Override
  public Integer call() throws Exception {

    assert !helpRequested;
    assert output != null;

    if (inputType != null || recording != null) {
      loadPromptOptions();
    }

    final Visitor visitor = Visitor.fromFile(source.getAbsolutePath());
    visitor.visit();

    Writer writer;
    if (output != null) {
      if (output.exists() && !output.delete()) {
        throw new IOException("Could not overwrite destination file '" + output.getName() + "'");
      }

      writer = new FileWriter(output);
    }
    else {
      // write to console
      writer = new OutputStreamWriter(System.out);
    }

    final BufferedWriter buffer = new BufferedWriter(writer);
    visitor.writeSchema(buffer);
    buffer.close();

    return 0;
  }

  private void loadPromptOptions() throws IOException {
    switch (inputType) {
      case "prompt" -> Prompt.get(Prompt.Factory.console());
      case "record" -> Prompt.get(Prompt.Factory.recorder());
      case "skip" -> Prompt.get(Prompt.Factory.yes());
      default -> {
        if (recording != null) {
          if (recording.exists() && !recording.delete()) {
            throw new IOException("Existing recording found, could not delete it");
          }

          final FileInputStream stream = new FileInputStream(recording);
          assert stream != null;

          final String[] recording = Recordings.fromInputStream(stream);
          assert recording.length > 0;

          Prompt.get(Prompt.Factory.player(recording));
        }
        else
          throw new IllegalStateException("Input type needs to be either 'prompt' or 'record', not: " + inputType);
      }
    }
  }

}

public class Main {
  public static void main(String[] args) throws IOException {
    int exitCode = new CommandLine(new VisitorCommand()).execute(args);
    System.exit(exitCode);
  }
}