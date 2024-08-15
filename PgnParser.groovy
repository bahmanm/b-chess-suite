#!/usr/bin/env groovy

import groovy.transform.*

/***************************************************************************/

class Id<T> {
  private final id = new Random().nextInt()

  Boolean equals(Id<T> other) {
    (this == other || this.id == other.id)
  }

  String toString() {
    "${id}"
  }
}

@TupleConstructor(excludes='id')
@EqualsAndHashCode
class Variation {
  Id<Variation> id = new Id<>()
  Id<Variation> parentId
  Id<Game> gameId
  Integer seqNo
  Integer startingPly

  String toString() {
    "Variation(${id}, Parent(${parentId}), Game(${gameId}), SeqNo(${seqNo}), StartingPly(${startingPly})))"
  }

  static Variation make(Map args) {
    def variation = new Variation(args)
    DB.VARIATIONS[variation.id] = variation
    variation
  }
}

@TupleConstructor(excludes='id')
@EqualsAndHashCode
class Move {
  Id<Move> id = new Id<>()
  Id<Variation> variationId
  Integer seqNo
  String san

  String toString() {
    "Move(${id}, Variation(${variationId}), SeqNo(${seqNo}), SAN(${san}))"
  }

  static Move make(Map args) {
    def move = new Move(args)
    DB.MOVES[move.id] = move
    move
  }
}

@TupleConstructor(excludes='id')
@EqualsAndHashCode
class Comment {
  Id<Comment> id = new Id<>()
  Id<Move> moveId = new Id<>()
  String nag
  String before
  String after

  String toString() {
    "Comment(${id}, Move(${moveId}), NAG(${nag}), Before(${before}), After(${after}))"
  }

  static Comment make(Map args) {
    def comment = new Comment(args)
    DB.COMMENTS[comment.id] = comment
    comment
  }
}

@EqualsAndHashCode
class Game {
  final Id<Game> id = new Id<>()

  String toString() {
    "Game(${id})"
  }

  static Game make() {
    def game = new Game()
    DB.GAMES[game.id] = game
    game
  }
}

@TupleConstructor(excludes='id')
@EqualsAndHashCode
class Tag {
  Id<Tag> id = new Id<>()
  Id<Game> gameId
  String text

  String toString() {
    "Tag(${id}, Game(${gameId}), Text(${text})))"
  }

  static Tag make(Map args) {
    def tag = new Tag(args)
    DB.TAGS[tag.id] = tag
    tag
  }
}

/***************************************************************************/

class DB {
  static final HashMap<Id<Variation>, Variation> VARIATIONS = [:]
  static final HashMap<Id<Move>, Move> MOVES = [:]
  static final HashMap<Id<Comment>, Comment> COMMENTS = [:]
  static final HashMap<Id<Game>, Game> GAMES = [:]
  static final HashMap<Id<Tag>, Tag> TAGS = [:]

  static void clear() {
    VARIATIONS.clear()
    MOVES.clear()
    COMMENTS.clear()
    GAMES.clear()
    TAGS.clear()
  }

  static String toString() {
    """DB(
  👉 GAMES(
\t${GAMES.values().join('\n\t')}
     ),
  👉 TAGS(
\t${TAGS.values().join('\n\t')}
     ),
  👉 VARIATIONS(
\t${VARIATIONS.values().join('\n\t')}
     ),
  👉 MOVES(
\t${MOVES.values().join('\n\t')}
     ),
  👉 COMMENTS(
\t${COMMENTS.values().join('\n\t')}
     )
)"""
  }
}



class Parser {

  class ParseStatus {
    Game currentGame = null

    Integer index = 0

    Boolean isParsingTags = false
    Boolean isInsideTag = false
    String currentTag = ''

    Stack<Move> moveStack = new Stack<>()
    Move currentMove = null

    Comment currentComment = null

    NestedVarations nested = new NestedVarations()

    class NestedVarations {
      Map<Id<Variation>, Integer> plyNoMap = [:]
      Map<Id<Variation>, Integer> seqNoMap = [:]
      Stack<Variation> variationStack = new Stack<>()
    }
  }

  void parse(String gameText) {
    def status =  new ParseStatus(currentGame: Game.make())
    def text = getFirstGame(gameText)

    status.isParsingTags = true
    parseTags(status, text)
    status.isParsingTags = false

    def mainVariation = Variation.make(parentId: null,
                                       gameId: status.currentGame.id,
                                       seqNo: 1,
                                       startingPly: 1)
    status.nested.variationStack.push(mainVariation)
    status.nested.seqNoMap[mainVariation.id] = 1
    status.nested.plyNoMap[mainVariation.id] = 1
    parseGame(status, text)
  }

  String getFirstGame(gameText) {
    gameText.split('(\\*|1-0|0-1|0.5-0.5|½-½)\\n+')[0]
  }

  void parseTags(ParseStatus status, String gameText) {
    for ( ; status.index < gameText.length(); status.index++) {
      def ch = gameText[status.index]
      if (ch == '[') {
        if (status.isInsideTag == false && status.isParsingTags == true) {
          status.isInsideTag = true
          continue
        } else {
          // ignore brackets used for formatting the comments
        }
      } else if (ch == ']') {
        if (status.isInsideTag == true && status.isParsingTags == true) {
          status.isInsideTag = false
          Tag.make(text: status.currentTag, gameId: status.currentGame.id)
          status.currentTag = ''
          continue
        } else {
          // ignore brackets used for formatting the comments
        }
      } else if (ch in ['\n', '\r', '\t', '"']) {
        // ignore redundant characters
      } else if (status.isInsideTag == true) {
        status.currentTag += ch
      } else {
        status.isParsingTags = false
        return
      }
    }
  }

  def parseComment(ParseStatus status, String gameText) {
    parseSemicolonComment(status, gameText)
    parseBracketComment(status, gameText)
  }

  def parseSemicolonComment(ParseStatus status, String gameText) {
    if (status.index >= gameText.length()) {
      return
    } else if (gameText[status.index] == ';') {
      def nextNewlineIndex = gameText.indexOf('\n', status.index + 1)
      if (nextNewlineIndex == -1)
        nextNewlineIndex = gameText.length()
      def commentText = gameText[(status.index + 1)..(nextNewlineIndex - 1)]
      if (status.currentMove == null) {
        status.currentComment = Comment.make(before: commentText)
      } else {
        status.currentComment = Comment.make(after: commentText, moveId: status.currentMove.id)
      }
      status.index = nextNewlineIndex
    }
  }

  def parseBracketComment(ParseStatus status, String gameText) {
    if (status.index >= gameText.length()) {
      return
    } else if (gameText[status.index] == '{') {
      def closingBracketIndex = gameText.indexOf('}', status.index + 1)
      if (closingBracketIndex == -1)
        closingBracketIndex = gameText.length()
      def commentText = gameText[(status.index + 1)..(closingBracketIndex - 1)]
      if (status.currentMove == null) {
        status.currentComment = Comment.make(before: commentText)
      } else {
        status.currentComment = Comment.make(after: commentText, moveId: status.currentMove.id)
      }
      status.index = closingBracketIndex + 1
    }
  }

  def skipWhitespace(ParseStatus status, String gameText) {
    if (status.index >= gameText.length()) {
      return
    } else if (gameText[status.index] =~ /\s/) {
      def lastWhitespaceIndex = gameText.indexOf(/\s/, status.index + 1)
      if (lastWhitespaceIndex == -1)
        lastWhitespaceIndex = status.index
      status.index = lastWhitespaceIndex + 1
    }
  }

  def parseMoves(ParseStatus status, String gameText) {
    if (status.index >= gameText.length()) {
      return
    }
    def nextBracketIndex = gameText.indexOf('{', status.index)
    if (nextBracketIndex == -1)
      nextBracketIndex = gameText.length() - 1
    def nextOpeningParenIndex = gameText.indexOf('(', status.index)
    if (nextOpeningParenIndex == -1)
      nextOpeningParenIndex = gameText.length() - 1
    def nextClosingParenIndex = gameText.indexOf(')', status.index)
    if (nextClosingParenIndex == -1)
      nextClosingParenIndex = gameText.length() - 1
    def nextSemicolonIndex = gameText.indexOf(';', status.index)
    if (nextSemicolonIndex == -1)
      nextSemicolonIndex = gameText.length() - 1
    def lastMoveIndex = [nextBracketIndex,
                         nextOpeningParenIndex,
                         nextClosingParenIndex,
                         nextSemicolonIndex].min() - 1
    if (lastMoveIndex < status.index)
      return
    def movesText = gameText[status.index..lastMoveIndex]
    def moveTokens = movesText.split(/(\s+|\n+|(\s*\d+\s*\.)|\.)/)
    def moves = moveTokens.findAll { token -> token }
    if (moves) {
      def currentVariation = status.nested.variationStack.peek()
      def currentVariationPlyNo = status.nested.plyNoMap[currentVariation.id]
      moves.eachWithIndex { san, n ->
        if (san.empty)
          return
        def move = Move.make(variationId: currentVariation.id,
                             seqNo: currentVariationPlyNo + n,
                             san: san)
        if (status.currentMove == null && status.currentComment != null) {
          status.currentComment.moveId = move.id
        }
        status.currentMove = move
      }
      status.nested.plyNoMap[currentVariation.id] = currentVariationPlyNo + moves.size() - 1
    }
    status.index = lastMoveIndex + 1
  }

  def parseVariation(ParseStatus status, String gameText)  {
    if (status.index >= gameText.length()) {
      return
    } else if (gameText[status.index] == ')') {
      while (gameText[status.index] == ')') {
        def parentId = status.nested.variationStack.pop().parentId
        status.nested.seqNoMap[parentId] += 1
        status.index += 1
      }
    } else if (gameText[status.index] == '(') {
      status.index += 1

      def parentVariation = status.nested.variationStack.empty ? null : status.nested.variationStack.peek()
      def plyNo = parentVariation ? status.nested.plyNoMap[parentVariation.id] : 1
      def seqNo = parentVariation ? status.nested.seqNoMap[parentVariation.id] : 1
      def variation = Variation.make(parentId: parentVariation?.id,
                                     gameId: status.currentGame.id,
                                     seqNo: seqNo,
                                     startingPly: plyNo)
      status.nested.plyNoMap[variation.id] = plyNo
      status.nested.seqNoMap[variation.id] = 1
      status.nested.variationStack.push(variation)
      parseMoves(status, gameText)
    } else {
      parseMoves(status, gameText)
    }
  }

  def parseGame(ParseStatus status, String gameText) {
    while (status.index < gameText.length()) {
      skipWhitespace(status, gameText)
      parseComment(status, gameText)
      skipWhitespace(status, gameText)
      parseVariation(status, gameText)
    }
  }

}

/***************************************************************************/
def test() {
  DB.clear()
  new Parser().parse(new File('PgnParser.sample-00.pgn').text)
  assert DB.VARIATIONS.size() == 5
  assert DB.MOVES.size() == 20
  assert DB.TAGS.size() == 12
}

test()
