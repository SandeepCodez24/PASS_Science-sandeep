#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#include "core/ScriptManager/SM_main.h"
#include "core/line_continuation.h"
#include "lexer/lexer.h"
#include "parser/parser.h"

static std::string normalizeInput(const std::string &raw)
{
  size_t start = 0;
  size_t end = raw.size();
  while (start < end && std::isspace(static_cast<unsigned char>(raw[start])))
    ++start;
  while (end > start && std::isspace(static_cast<unsigned char>(raw[end - 1])))
    --end;
  std::string s = raw.substr(start, end - start);
  bool looksLikeWindowsDrive =
      (s.size() >= 2 && std::isalpha(static_cast<unsigned char>(s[0])) &&
       s[1] == ':');
  bool surroundedByQuotes =
      (s.size() >= 2 && ((s.front() == '"' && s.back() == '"') ||
                         (s.front() == '\'' && s.back() == '\'')));
  if (surroundedByQuotes)
  {
    if (!looksLikeWindowsDrive || (s.size() >= 3))
      s = s.substr(1, s.size() - 2);
  }
  return s;
}

static std::string stripQuotes(std::string s)
{
  if (s.size() >= 2 && ((s.front() == '"' && s.back() == '"') ||
                        (s.front() == '\'' && s.back() == '\'')))
  {
    return s.substr(1, s.size() - 2);
  }
  return s;
}

void showSuggestions(const std::string &input) { (void)input; }

static bool isEmptyLine(const std::string &s)
{
  return s.find_first_not_of(" \t\r\n") == std::string::npos;
}

int main(int argc, char *argv[])
{
  Parser parser;
  std::filesystem::path launchWorkingDirectory =
      std::filesystem::current_path();
  std::filesystem::path applicationDirectory;
  if (argc > 0 && argv[0] != nullptr)
  {
    std::filesystem::path executablePath =
        std::filesystem::absolute(argv[0]).lexically_normal();
    applicationDirectory = executablePath.parent_path();
  }

  ScriptManager manager(applicationDirectory, launchWorkingDirectory);
  manager.init();

  if (argc > 1)
  {
    std::ostringstream argBuilder;
    for (int i = 1; i < argc; ++i)
    {
      if (i > 1)
        argBuilder << ' ';
      argBuilder << argv[i];
    }
    std::string rawArg = argBuilder.str();
    if (manager.tryExecuteInputAsFile(rawArg))
    {
      return 0;
    }
    std::cerr << "Error: Failed to execute file: " << rawArg << std::endl;
    return 1;
  }

  std::cin.clear();
  // Same-type nesting depth for block accumulation — see the matching
  // comment in ScriptManager/SM_stage_02.cpp's executeFile() for why this
  // is needed (a nested block of the same type shares its terminator
  // keyword with the outer one).
  int replIfDepth = 0;
  int replForDepth = 0;
  int replWhileDepth = 0;

  while (true)
  {
    std::string input;
    std::cout << ">> " << std::flush;
    if (!std::getline(std::cin, input))
    {
      std::cout << "\n";
      break;
    }
    input = readLogicalLine(std::cin, input);
    if (isEmptyLine(input))
      continue;
    std::string trimmedInput = normalizeInput(input);
    if (trimmedInput.empty())
      continue;

    // --------- If-block accumulation mode ---------
    if (parser.inIfBlock())
    {
      std::string trimmed = trimmedInput;
      std::string lower = trimmed;
      for (char &ch : lower)
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
      bool isNestedIfHeader = lower.rfind("if", 0) == 0 &&
          (lower.size() == 2 || std::isspace((unsigned char)lower[2]) || lower[2] == '(');
      if (isNestedIfHeader)
      {
        replIfDepth++;
        parser.feedIfLine(input);
      }
      else if (replIfDepth == 0 && (lower == "else" || lower == "else:"))
      {
        parser.setElseBranch();
      }
      else if (replIfDepth == 0 && lower.rfind("elseif", 0) == 0 &&
               (lower.size() == 6 || std::isspace((unsigned char)lower[6])))
      {
        parser.setElseifBranch(trimmed);
      }
      else if (Parser::isIfTerm(lower))
      {
        if (replIfDepth > 0)
        {
          replIfDepth--;
          parser.feedIfLine(input);
        }
        else
        {
          parser.executeIfBlock();
          parser.consumeTopLevelStopSignal();
        }
      }
      else
      {
        parser.feedIfLine(input);
      }
      continue;
    }

    // --------- For-loop block accumulation mode ---------
    if (parser.inForBlock())
    {
      std::string trimmed = trimmedInput;
      std::string lower = trimmed;
      for (char &ch : lower)
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
      bool isNestedForHeader = lower.rfind("for", 0) == 0 &&
          (lower.size() == 3 || std::isspace((unsigned char)lower[3]));
      if (isNestedForHeader)
      {
        replForDepth++;
        parser.feedForLine(input);
      }
      else if (Parser::isForTerm(lower))
      {
        if (replForDepth > 0)
        {
          replForDepth--;
          parser.feedForLine(input);
        }
        else
        {
          parser.executeForBlock();
          parser.consumeTopLevelStopSignal();
        }
      }
      else
      {
        parser.feedForLine(input);
      }
      continue;
    }

    // --------- While-loop block accumulation mode ---------
    if (parser.inWhileBlock())
    {
      std::string trimmed = trimmedInput;
      std::string lower = trimmed;
      for (char &ch : lower)
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
      bool isNestedWhileHeader = lower.rfind("while", 0) == 0 &&
          (lower.size() == 5 || std::isspace((unsigned char)lower[5]) || lower[5] == '(');
      if (isNestedWhileHeader)
      {
        replWhileDepth++;
        parser.feedWhileLine(input);
      }
      else if (Parser::isWhileTerm(lower))
      {
        if (replWhileDepth > 0)
        {
          replWhileDepth--;
          parser.feedWhileLine(input);
        }
        else
        {
          parser.executeWhileBlock();
          parser.consumeTopLevelStopSignal();
        }
      }
      else
      {
        parser.feedWhileLine(input);
      }
      continue;
    }

    // --------- Function-definition block accumulation mode ---------
    if (parser.inFunctionBlock())
    {
      std::string trimmed = trimmedInput;
      std::string lower = trimmed;
      for (char &ch : lower)
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
      if (Parser::isFunctionTerm(lower))
      {
        parser.executeFunctionDefEnd();
      }
      else
      {
        parser.feedFunctionLine(input);
      }
      continue;
    }

    // --------- Class-definition block accumulation mode ---------
    if (parser.inClassBlock())
    {
      std::string trimmed = trimmedInput;
      std::string lower = trimmed;
      for (char &ch : lower)
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
      if (Parser::isClassTerm(lower))
      {
        parser.executeClassDefEnd();
      }
      else
      {
        parser.feedClassLine(input);
      }
      continue;
    }

    // --------- Internal command isolation (IDE hook) ---------
    // IDE sends commands using: @cmd setpwd "..."
    if (trimmedInput.rfind("@cmd ", 0) == 0)
    {
      std::string cmd = trimmedInput.substr(5);
      if (cmd.rfind("setpwd ", 0) == 0)
      {
        std::string path = cmd.substr(7);
        path = stripQuotes(path);
        manager.setWorkspaceDirectory(std::filesystem::path(path));
      }
      // ============================================================
      // NEW (CLI wiring): explicit workspace resync hook. The Java IDE
      // can send "@cmd syncws" to force the persistent REPL to reload
      // variables.txt from disk, e.g. after an editor-side action that
      // touched the shared workspace. Harmless if nothing changed.
      // ============================================================
      else if (cmd == "syncws")
      {
        parser.loadFromFile();
      }
      continue;
    }

    // --------- Builtins / interpreter commands ---------
    if (trimmedInput == "exit")
      break;
    if (trimmedInput == "clear" || trimmedInput == "cls")
    {
      std::ofstream reset("variables.txt");
      reset << "NAME        TYPE        VALUE\n";
      reset << "-----------------------------------------\n";
      reset.close();
      // Keep the live REPL memory in step with the file we just cleared, so
      // a subsequent expression doesn't resurrect stale variables.
      parser.loadFromFile();
      continue;
    }
    if (trimmedInput.rfind("addpath ", 0) == 0)
    {
      std::string path = trimmedInput.substr(8);
      path = stripQuotes(path);
      manager.addPath(path);
      continue;
    }
    if (trimmedInput.rfind("rmpath ", 0) == 0)
    {
      std::string path = trimmedInput.substr(7);
      path = stripQuotes(path);
      manager.removePath(path);
      continue;
    }
    if (trimmedInput == "rehash" || trimmedInput == "reindex")
    {
      manager.rehash();
      std::cout << "Rehashed script cache\n";
      continue;
    }
    if (trimmedInput == "path")
    {
      manager.printPath();
      continue;
    }
    if (trimmedInput == "pwd")
    {
      manager.printPwd();
      continue;
    }
    if (trimmedInput.rfind("whichall ", 0) == 0)
    {
      manager.whichAll(trimmedInput.substr(9));
      continue;
    }
    if (trimmedInput.rfind("which ", 0) == 0)
    {
      std::string argument = trimmedInput.substr(6);
      if (argument.rfind("-all ", 0) == 0)
        manager.whichAll(argument.substr(5));
      else
        manager.which(argument);
      continue;
    }

    // --------- Script name execution by workspace search ---------
    if (manager.tryExecuteInputAsFile(trimmedInput))
    {
      // ============================================================
      // CORE SHARED-WORKSPACE FIX
      // ------------------------------------------------------------
      // executeFile() runs the script on a FRESH, local Parser instance
      // (see SM_stage_02.cpp). That instance loads+saves variables.txt,
      // so on disk the global workspace is now correct — but THIS long-
      // lived REPL parser's in-memory `memory` map is stale and does not
      // contain the script's variables. Without this reload, running a
      // script by name (e.g. "A") and then referencing its variable
      // (e.g. "det(XX)") in the CLI would fail with "undefined".
      //
      // loadFromFile() only rebuilds the variable store (memory +
      // insertionOrder); it does NOT touch registered functions/classes,
      // so interactively-defined helpers survive the resync.
      //
      // Requires: Parser::loadFromFile() must be PUBLIC in parser.h
      // (it is already defined in variable_store.cpp). See INTEGRATION
      // notes.
      // ============================================================
      parser.loadFromFile();
      continue;
    }

    // --------- Normal expression execution ---------
    showSuggestions(input);
    Lexer lexer(trimmedInput);
    auto tokens = lexer.tokenize();
    parser.setTokens(tokens);
    parser.parse();
    // parse() persists the (possibly updated) workspace to variables.txt via
    // saveToFile(), which is exactly what the Java Explorer reloads when the
    // ">> " prompt is re-emitted below.
    parser.consumeTopLevelStopSignal();
  }
  return 0;
}
