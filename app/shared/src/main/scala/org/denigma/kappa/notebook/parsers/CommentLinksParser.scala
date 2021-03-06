package org.denigma.kappa.notebook.parsers

import fastparse.all._

trait BasicParser {
  protected val optSpaces = P(CharIn(" \t").rep(min = 0))
  protected val spaces = P(CharIn(" \t").rep(min = 1))
  protected val digit = P(CharIn('0'to'9'))
  protected val letter = P(CharIn('A' to 'Z') | CharIn('a' to 'z'))
}

class CommentLinksParser extends BasicParser
{
  protected val propertyComment = P("#^")
  protected val commentSign = P( "#".rep(min = 1) ~ "^".?)
  protected val notComment = P( ! commentSign ).flatMap(v => AnyChar)
  protected val bracketsOrSpace = P("<" | ">" | " ")
  protected val notBracketsOrSpace = CharPred(ch => ch != '<' && ch != '>' && ch != ' ')
  protected val protocol = P( ("http" | "ftp" ) ~ "s".? ~ "://" )
  val comment = notComment.rep  ~ commentSign ~ AnyChar.rep.!
  val link: P[String] = P(optSpaces ~ "<".? ~ (protocol ~ notBracketsOrSpace.rep).! ~ ">".? ) //map(_.toString)
  val linkAfterComment: Parser[String] = P( notComment.rep  ~ commentSign ~ optSpaces ~ link )


}

class ImageParser extends BasicParser {
  val image = P( optSpaces ~  ":image" ~ spaces ~AnyChar.rep.! )
  //val img = P( optSpaces ~  ":image" ~ spaces ~AnyChar.! )
}

class VideoParser extends BasicParser {
  val video = P( optSpaces ~  ":video" ~ spaces ~AnyChar.rep.! )
}