# Funny lexer generator: regex → NFA → DFA → minimized DFA

Homework 1 solution: builds a DFA-based lexer for the toy language "Funny"
from a list of token regexes, minimizes it, exports a transition table, and
runs it against a battery of edge-case tests.

## Build & run

Requires a JDK (11+). No external dependencies.

```bash
javac *.java
java Main
```

This will:

1. Build the combined NFA (Thompson construction) for all token regexes.
2. Convert it to a DFA (subset construction) and print its size.
3. Minimize the DFA (Hopcroft-style partition refinement) and print the new size.
4. Export the minimized DFA's transition table to `dfa_table.json`.
5. Run the built-in test suite and print PASS/FAIL for each case.

## Files

| File                    | Role |
|-------------------------|------|
| `NFA.java`               | NFA representation (adjacency list, epsilon = -1) |
| `RegexToNFA.java`        | Recursive-descent regex parser → Thompson NFA fragment |
| `TokenSpec.java`         | One lexical rule: name, regex, skip flag |
| `SubsetConstruction.java`| NFA → DFA (epsilon-closure, subset construction), builds an explicit trap state |
| `Hopcroft.java`          | DFA minimization by partition refinement |
| `DFA.java`               | DFA representation (total transition table) |
| `LexerRunner.java`       | Maximal-munch scanner driven by the DFA table |
| `Export.java`            | Serializes the DFA + token table to JSON |
| `Main.java`              | Token spec for Funny, wiring, and the test suite |

## Regex mini-language supported

Enough to express all of the required token classes:

- literals, escapes (`\n \t \r \\ \( \) \[ \] \{ \} \* \+ \? \. \|`)
- concatenation, alternation `a|b`
- `*`, `+`, `?`
- grouping `(...)`
- character classes `[a-z]`, negation `[^\n]`
- `.` (any ASCII character)

## Token specification (priority order matters for ties)

```
KEYWORD  function | returns | while | if | else | assert | assume | invariant | length
IDENT    [a-zA-Z_][a-zA-Z0-9_]*
INT      0 | [1-9][0-9]*
WS       [ \t\r\n]+            (skip)
COMMENT  //[^\n]*               (skip)
OP       ( ) [ ] { } , == != <= >= + - * / < > =
```

Keywords are listed before `IDENT`, so on an exact-length tie ("function"
matches both the keyword rule and `IDENT`) the keyword wins — standard
lex/flex "first rule wins" tie-break. Multi-character operators (`==`, `!=`,
`<=`, `>=`) don't need any special ordering: the scanner always takes the
**longest** match from the current position (maximal munch), so `==` beats
a lone `=` automatically as long as the input keeps matching.

The alphabet is fixed to ASCII (0–127). Any character outside that range,
or any character that can't extend or start a valid token, drives the
automaton into an explicit trap state; the scanner reports it as a
single-character lexical error and resynchronizes at the next character.

## Report

Run `java Main` to see the exact numbers for the current build, e.g. on a
sample run:

```
NFA states:                     179
DFA states before minimization:  78
DFA states after minimization:   75
```

(Minimization here is modest because most operator branches are already
distinct short chains; the bigger DFA size comes from the ASCII fan-out at
each character-class node — the JSON table only records non-trap edges to
stay compact.)

22/22 built-in tests pass, covering: empty input; whitespace-only input
(spaces/tabs/CRLF); `0` / `00` / `01` (leading-zero rule for INT); identifiers
with `_`; all nine keywords; an identifier that merely starts like a keyword
(`iffy`); every delimiter and operator, including the two-character ones;
a `//` comment running to end of line; a non-ASCII character falling into
the trap; and a small end-to-end snippet
(`function foo(x, y) returns x`).

## Reusing the table in the project's lexer (T0)

`dfa_table.json` has everything needed to drive a scanner without re-running
regex compilation at runtime:

```json
{
  "alphabet": "ASCII 0-127",
  "numStates": 75,
  "start": 2,
  "trap": 0,
  "tokens": [ {"index": 0, "name": "KEYWORD", "skip": false, "regex": "function"}, ... ],
  "accepting": { "<state>": <tokenIndex>, ... },
  "transitions": [ {"state": <id>, "edges": [[<symbolCode>, <targetState>], ...]}, ... ]
}
```

Any edge not listed for a state implicitly goes to `trap`. A lexer just
needs to: start at `start`, follow `transitions` per input byte, remember the
last state that was in `accepting` (and the matched length), and on getting
stuck, cut the token there (or emit a single-character error if nothing
was ever accepted).
