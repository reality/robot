package org.obolibrary.robot;

import java.io.IOException;
import java.io.Writer;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxClassExpressionParser;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO:
 * - Allow DL class expressions in data cells.
 *   - Mostly done, but some testing needed, including basic regex parsing.
 * - Allow TSVs as well as CSVs to be passed.
 * - Follow logging conventions in:
 *     https://github.com/ontodev/robot/blob/master/CONTRIBUTING.md#documenting-errors
 * - Make the reasoner choice configurable via the command line (see the way other commands do it)
 * - Eventually extend to Excel
 * - Eventually need to tweak the command line options to be more consistent with the other commands
 *   and work seamlessly with robot's chaining feature.
 */

/**
 * Implements the validate operation for a given CSV file and ontology.
 *
 * @author <a href="mailto:consulting@michaelcuffaro.com">Michael E. Cuffaro</a>
 */
public class ValidateOperation {
  /** Logger */
  private static final Logger logger = LoggerFactory.getLogger(ValidateOperation.class);

  /** Output writer */
  private static Writer writer;

  /** INSERT DOC HERE */
  private static OWLDataFactory dataFactory;

  /** The ontology to use for validation */
  private static OWLOntology ontology;

  /** The reasoner to use for validation */
  private static OWLReasoner reasoner;

  /** The parser to use for evaluating class expressions */
  private static ManchesterOWLSyntaxClassExpressionParser parser;

  /** A map from rdfs:labels to IRIs */
  private static Map<String, IRI> label_to_iri_map;

  /** A map from IRIs to rdfs:labels */
  private static Map<IRI, String> iri_to_label_map;

  /** The row number of the CSV data currently being processed */
  private static int csv_row_index;

  /** The column number of the CSV data currently being processed */
  private static int csv_col_index;

  /**
   * An enum representation of the different categories of rules. We distinguish between queries,
   * which involve queries to a reasoner, and presence rules, which check for the existence of
   * content in a cell.
   */
  private enum RCatEnum {
    QUERY,
    PRESENCE
  }

  /**
   * An enum representation of the different types of rules. Each rule type belongs to larger
   * category, and is identified within the CSV file by a particular string.
   */
  private enum RTypeEnum {
    DIRECT_SUPER("direct-superclass-of", RCatEnum.QUERY),
    SUPER("superclass-of", RCatEnum.QUERY),
    EQUIV("equivalent-to", RCatEnum.QUERY),
    DIRECT_SUB("direct-subclass-of", RCatEnum.QUERY),
    SUB("subclass-of", RCatEnum.QUERY),
    DIRECT_INSTANCE("direct-instance-of", RCatEnum.QUERY),
    INSTANCE("instance-of", RCatEnum.QUERY),
    REQUIRED("is-required", RCatEnum.PRESENCE),
    EXCLUDED("is-excluded", RCatEnum.PRESENCE);

    private final String ruleType;
    private final RCatEnum ruleCat;

    RTypeEnum(String ruleType, RCatEnum ruleCat) {
      this.ruleType = ruleType;
      this.ruleCat = ruleCat;
    }

    public String getRuleType() {
      return ruleType;
    }

    public RCatEnum getRuleCat() {
      return ruleCat;
    }
  }

  /** Reverse map from rule types (as Strings) to RTypeEnums, populated at load time */
  private static final Map<String, RTypeEnum> rule_type_to_rtenum_map = new HashMap<>();

  static {
    for (RTypeEnum r : RTypeEnum.values()) {
      rule_type_to_rtenum_map.put(r.getRuleType(), r);
    }
  }

  /**
   * Reverse map from rule types in the QUERY category (as Strings) to RTypeEnums, populated at load
   * time
   */
  private static final Map<String, RTypeEnum> query_type_to_rtenum_map = new HashMap<>();

  static {
    for (RTypeEnum r : RTypeEnum.values()) {
      if (r.getRuleCat() == RCatEnum.QUERY) {
        query_type_to_rtenum_map.put(r.getRuleType(), r);
      }
    }
  }

  /** Enum used with the writelog() method */
  private enum LogLevel {
    DEBUG,
    ERROR,
    INFO,
    WARN;
  }

  /**
   * Given the string `format`, a logging level specification, and a number of formatting variables,
   * use the formating variables to fill in the format string in the manner of C's printf function,
   * and write to the log at the appropriate log level. If the parameter `showCoords` is true, then
   * include the current row and column number in the output string.
   */
  private static void writelog(
      boolean showCoords, LogLevel logLevel, String format, Object... positionalArgs) {
    String logStr = "";
    if (showCoords) {
      logStr += String.format("At row: %d, column: %d: ", csv_row_index + 1, csv_col_index + 1);
    }

    logStr += String.format(format, positionalArgs);
    switch (logLevel) {
      case ERROR:
        logger.error(logStr);
        break;
      case WARN:
        logger.warn(logStr);
        break;
      case INFO:
        logger.info(logStr);
        break;
      case DEBUG:
        logger.debug(logStr);
        break;
    }
  }

  /**
   * Given the string `format`, a logging level specification, and a number of formatting variables,
   * use the formating variables to fill in the format string in the manner of C's printf function,
   * and write to the log at the appropriate log level, including the current row and column number
   * in the output string.
   */
  private static void writelog(LogLevel logLevel, String format, Object... positionalArgs) {
    writelog(true, logLevel, format, positionalArgs);
  }

  /**
   * Given the string `format` and a number of formatting variables, use the formatting variables to
   * fill in the format string in the manner of C's printf function, and write the string to the
   * Writer object that belongs to ValidateOperation. If the parameter `showCoords` is true, then
   * include the current row and column number in the output string.
   */
  private static void writeout(boolean showCoords, String format, Object... positionalArgs)
      throws IOException {

    String outStr = "";
    if (showCoords) {
      outStr += String.format("At row: %d, column: %d: ", csv_row_index + 1, csv_col_index + 1);
    }
    outStr += String.format(format, positionalArgs);
    writer.write(outStr + "\n");
  }

  /**
   * Given the string `format` and a number of formatting variables, use the formatting variables to
   * fill in the format string in the manner of C's printf function, and write the string to the
   * Writer object that belongs to ValidateOperation, including the current row and column number in
   * the output string.
   */
  private static void writeout(String format, Object... positionalArgs) throws IOException {
    writeout(true, format, positionalArgs);
  }

  /**
   * Given an ontology, a reasoner factory, and an output writer, initialise the static variables
   * belonging to ValidateOperation: The shared ontology, output writer, manchester syntax class
   * expression parser, and the teo maps from the ontology's IRIs to rdfs:labels and vice versa.
   */
  private static void initialize(
      OWLOntology ontology, OWLReasonerFactory reasonerFactory, Writer writer) throws IOException {

    ValidateOperation.ontology = ontology;
    ValidateOperation.writer = writer;

    // Robot's custom quoted entity checker will be used for parsing class expressions:
    QuotedEntityChecker checker = new QuotedEntityChecker();
    // Add the class that will be used for IO and for handling short-form IRIs by the quoted entity
    // checker:
    checker.setIOHelper(new IOHelper());
    checker.addProvider(new SimpleShortFormProvider());

    // Initialise the dataFactory and add rdfs:label to the list of annotation properties which
    // will be looked up in the ontology by the quoted entity checker when finding names.
    dataFactory = OWLManager.getOWLDataFactory();
    checker.addProperty(dataFactory.getRDFSLabel());
    checker.addAll(ValidateOperation.ontology);

    // Create the parser using the data factory and entity checker.
    ValidateOperation.parser = new ManchesterOWLSyntaxClassExpressionParser(dataFactory, checker);

    // Use the given reasonerFactory to initialise the reasoner based on the given ontology:
    reasoner = reasonerFactory.createReasoner(ValidateOperation.ontology);

    // Extract from the ontology two maps from rdfs:labels to IRIs and vice versa:
    ValidateOperation.iri_to_label_map = OntologyHelper.getLabels(ValidateOperation.ontology);
    ValidateOperation.label_to_iri_map = reverse_iri_label_map(ValidateOperation.iri_to_label_map);
  }

  /** Deallocate any static variables that need to be deallocated. */
  private static void tearDown() {
    reasoner.dispose();
  }

  /** Given a map from IRIs to strings, return its inverse. */
  private static Map<String, IRI> reverse_iri_label_map(Map<IRI, String> source) {
    HashMap<String, IRI> target = new HashMap();
    for (Map.Entry<IRI, String> entry : source.entrySet()) {
      String reverseKey = entry.getValue();
      IRI reverseValue = entry.getKey();
      if (target.containsKey(reverseKey)) {
        writelog(
            LogLevel.WARN,
            "Duplicate rdfs:label \"%s\". Overwriting value \"%s\" with \"%s\"",
            reverseKey,
            target.get(reverseKey),
            reverseValue);
      }
      target.put(reverseKey, reverseValue);
    }
    return target;
  }

  /**
   * Given a string specifying a list of rules of various types, return a map which contains, for
   * each rule type present in the string, the list of rules of that type that have been specified.
   */
  private static Map<String, List<String>> parse_rules(String ruleString) {
    HashMap<String, List<String>> ruleMap = new HashMap();
    // Skip over empty strings and strings that start with "##".
    if (!ruleString.trim().equals("") && !ruleString.trim().startsWith("##")) {
      // Rules are separated by semicolons:
      String[] rules = ruleString.split("\\s*;\\s*");
      for (String rule : rules) {
        // Skip any rules that begin with a '#' (comments):
        if (rule.trim().startsWith("#")) {
          continue;
        }
        // Each rule is of the form: <rule-type> <rule-content> but for the PRESENCE category, if
        // <rule-content> is left out it is implicitly understood to be "true"
        String[] ruleParts = rule.trim().split("\\s+", 2);
        String ruleType = ruleParts[0].trim();
        String ruleContent = null;
        if (ruleParts.length == 2) {
          ruleContent = ruleParts[1].trim();
        } else {
          RTypeEnum rTypeEnum = rule_type_to_rtenum_map.get(ruleType);
          if (rTypeEnum != null && rTypeEnum.getRuleCat() == RCatEnum.PRESENCE) {
            ruleContent = "true";
          } else {
            writelog(LogLevel.ERROR, "Invalid rule: %s", rule.trim());
            continue;
          }
        }

        // Add, to the map, an empty list for the given ruleType if we haven't seen it before:
        if (!ruleMap.containsKey(ruleType)) {
          ruleMap.put(ruleType, new ArrayList<String>());
        }
        // Add the content of the given rule to the list of rules corresponding to its ruleType:
        ruleMap.get(ruleType).add(ruleContent);
      }
    }
    return ruleMap;
  }

  /**
   * Given a string of substrings split by pipes ('|'), return an array with the first substring in
   * the 0th position of the array, the second substring in the 1st position, and so on.
   */
  private static String[] split_on_pipes(String ruleType) {
    // A rule type can be of the form: ruletype1|ruletype2|ruletype3...
    // where the first one is the primary type for lookup purposes:
    return ruleType.split("\\s*\\|\\s*");
  }

  /**
   * Given a string describing a compound rule type, return the primary rule type of the compound
   * rule type.
   */
  private static String get_primary_rule_type(String ruleType) {
    return split_on_pipes(ruleType)[0];
  }

  /**
   * Given a string describing a rule type, return a boolean indicating whether it is one of the
   * rules recognised by ValidateOperation.
   */
  private static boolean rule_type_recognised(String ruleType) {
    return rule_type_to_rtenum_map.containsKey(get_primary_rule_type(ruleType));
  }

  /**
   * Given a string describing one of the classes in the ontology, in either the form of an IRI, an
   * abbreviated IRI, or an rdfs:label, return the rdfs:label for that class.
   */
  private static String get_label_from_term(String term) {
    if (term == null) {
      return null;
    }

    // Remove any surrounding single quotes from the term:
    term = term.replaceAll("^\'|\'$", "");

    // If the term is already a recognised label, then just send it back:
    if (label_to_iri_map.containsKey(term)) {
      return term;
    }

    // Check to see if the term is a recognised IRI (possibly in short form), and if so return its
    // corresponding label:
    for (IRI iri : iri_to_label_map.keySet()) {
      if (iri.toString().equals(term) || iri.getShortForm().equals(term)) {
        return iri_to_label_map.get(iri);
      }
    }

    // If the label isn't recognised, just return null:
    return null;
  }

  /** INSERT DOC HERE */
  private static OWLClassExpression get_class_expression_from_string(String term) {
    OWLClassExpression ce;
    try {
      ce = parser.parse(term);
    } catch (OWLParserException e) {
      // If the parsing fails the first time, try surrounding the term in single quotes:
      try {
        ce = parser.parse("'" + term + "'");
      } catch (OWLParserException ee) {
        writelog(
            LogLevel.ERROR,
            "Could not determine class expression from \"%s\".\n\t%s.",
            term,
            e.getMessage().trim());
        return null;
      }
    }
    return ce;
  }

  /**
   * Given a string in the form of a wildcard, and a list of strings representing a row of the CSV,
   * return the rdfs:label contained in the position of the row indicated by the wildcard.
   */
  private static String get_wildcard_contents(String wildcard, List<String> row) {
    if (!wildcard.startsWith("%")) {
      writelog(LogLevel.ERROR, "Invalid wildcard: \"%s\".", wildcard);
      return null;
    }

    int colIndex = Integer.parseInt(wildcard.substring(1)) - 1;
    if (colIndex >= row.size()) {
      writelog(
          LogLevel.ERROR,
          "Rule: \"%s\" indicates a column number that is greater than the row length (%d).",
          wildcard,
          row.size());
      return null;
    }

    String term = row.get(colIndex).trim();
    if (term == null) {
      writelog(
          LogLevel.WARN,
          "Failed to retrieve label from wildcard: %s. No term at position %d of this row.",
          wildcard,
          colIndex + 1);
      return null;
    }

    if (term.equals("")) {
      writelog(
          LogLevel.WARN,
          "Failed to retrieve label from wildcard: %s. Term at position %d of row is empty.",
          wildcard,
          colIndex + 1);
      return null;
    }

    return term;
  }

  /**
   * Given a string, possibly containing wildcards, and a list of strings representing a row of the
   * CSV, return a string in which all of the wildcards in the input string have been replaced by
   * the rdfs:labels corresponding to the content in the positions of the row that they indicate.
   */
  private static String interpolate(String str, List<String> row) {
    if (str.trim().equals("")) {
      return str.trim();
    }

    String interpolatedString = "";

    // Look for any substrings starting with a percent-symbol and followed by a number:
    Matcher m = Pattern.compile("%\\d+").matcher(str);
    int currIndex = 0;
    while (m.find()) {
      // Get the term from the row that corresponds to the given wildcard:
      String term = get_wildcard_contents(m.group(), row);

      // Iteratively build the interpolated string up to the current term, which we try to convert
      // into a label. If conversion to a label is possible, enclose it in single quotes, otherwise
      // enclose it in parentheses:
      String label = get_label_from_term(term);
      if (label != null) {
        interpolatedString =
            interpolatedString + str.substring(currIndex, m.start()) + "'" + label + "'";
      } else {
        interpolatedString =
            interpolatedString + str.substring(currIndex, m.start()) + "(" + term + ")";
      }
      currIndex = m.end();
    }
    // There may be text after the final wildcard, so add it now:
    interpolatedString += str.substring(currIndex);
    return interpolatedString;
  }

  /**
   * Given a string describing the content of a rule and a string describing its rule type, return a
   * simple map entry such that the `key` for the entry is the main clause of the rule, and the
   * `value` for the entry is a list of the rule's when-clauses. Each when-clause is itself stored
   * as an array of three strings, including the class to which the when-clause is to be applied,
   * the rule type for the when clause, and the actual axiom to be validated against the class.
   */
  private static SimpleEntry<String, List<String[]>> separate_rule(String rule, String ruleType) {
    // Check if there are any when clauses:
    Matcher m = Pattern.compile("(\\(\\s*when\\s+.+\\))(.*)").matcher(rule);
    String whenClauseStr = null;
    if (!m.find()) {
      // If there is no when clause, then just return back the rule string as it was passed with an
      // empty when clause list:
      writelog(LogLevel.INFO, "No when-clauses found in rule: \"%s\".", rule);
      return new SimpleEntry<String, List<String[]>>(rule, new ArrayList<String[]>());
    }

    // If there is no main clause and this is not a PRESENCE rule, inform the user of the problem
    // and return the rule string as it was passed with an empty when clause list:
    if (m.start() == 0 && rule_type_to_rtenum_map.get(ruleType).getRuleCat() != RCatEnum.PRESENCE) {
      writelog(LogLevel.ERROR, "Rule: \"%s\" has when clause but no main clause.", rule);
      return new SimpleEntry<String, List<String[]>>(rule, new ArrayList<String[]>());
    }

    whenClauseStr = m.group(1);
    // Extract the actual content of the when-clause.
    whenClauseStr = whenClauseStr.substring("(when ".length(), whenClauseStr.length() - 1);

    // Don't fail just because there is some extra garbage at the end of the rule, but notify
    // the user about it:
    if (!m.group(2).trim().equals("")) {
      writelog(
          LogLevel.WARN, "Ignoring string \"%s\" at end of rule \"%s\".", m.group(2).trim(), rule);
    }

    // Within each when clause, multiple subclauses separated by ampersands are allowed. Each
    // subclass must be of the form: <Entity> <Rule-Type> <Axiom>.
    // <Entity> is in the form of a (not necessaruly interpolated) label: either a contiguous string
    // or a string with whitespace enclosed in single quotes. <Rule-Type> is a possibly hyphenated
    // alphanumeric string. <Axiom> can take any form. Here we resolve each sub-clause of the
    // when statement into a list of such triples.
    ArrayList<String[]> whenClauses = new ArrayList();
    for (String whenClause : whenClauseStr.split("\\s*&\\s*")) {
      m = Pattern.compile("^([^\'\\s]+|\'[^\']+\')\\s+([a-z\\-\\|]+)\\s+(.*)$").matcher(whenClause);

      if (!m.find()) {
        writelog(LogLevel.ERROR, "Unable to decompose when-clause: \"%s\".", whenClause);
        // Return the rule as passed with an empty when clause list:
        return new SimpleEntry<String, List<String[]>>(rule, new ArrayList<String[]>());
      }
      // Add the triple to the list of when clauses:
      whenClauses.add(new String[] {m.group(1), m.group(2), m.group(3)});
    }

    // Now get the main part of the rule (i.e. the part before the when clause):
    m = Pattern.compile("^(.+)\\s+\\(when\\s").matcher(rule);
    if (m.find()) {
      return new SimpleEntry<String, List<String[]>>(m.group(1), whenClauses);
    }

    // If no main clause is found, then if this is a PRESENCE rule, implicitly assume that the main
    // clause is "true":
    if (rule_type_to_rtenum_map.get(ruleType).getRuleCat() == RCatEnum.PRESENCE) {
      return new SimpleEntry<String, List<String[]>>("true", whenClauses);
    }

    // We should never get here since we have already checked for an empty main clause earlier ...
    writelog(
        LogLevel.ERROR,
        "Encountered unknown error while looking for main clause of rule \"%s\".",
        rule);
    // Return the rule as passed with an empty when clause list:
    return new SimpleEntry<String, List<String[]>>(rule, new ArrayList<String[]>());
  }

  /** INSERT DOC HERE */
  private static boolean validate_when_clauses(List<String[]> whenClauses, List<String> row)
      throws Exception {

    for (String[] whenClause : whenClauses) {
      String subject = interpolate(whenClause[0], row).trim();
      writelog(LogLevel.INFO, "Interpolated: \"%s\" into \"%s\"", whenClause[0], subject);

      // If the subject term is blank, then skip this clause:
      if (subject.equals("")) {
        continue;
      }

      // Make sure all of the rule types in the when clause are of the right category:
      String whenRuleType = whenClause[1];
      for (String whenRuleSubType : split_on_pipes(whenRuleType)) {
        RTypeEnum whenSubRType = rule_type_to_rtenum_map.get(whenRuleSubType);
        if (whenSubRType == null || whenSubRType.getRuleCat() != RCatEnum.QUERY) {
          writelog(
              LogLevel.ERROR,
              "In clause: \"%s\": Only rules of type: %s are allowed in a when clause.",
              whenRuleType,
              query_type_to_rtenum_map.keySet());
          return false;
        }
      }

      // Interpolate the axiom to validate and send the query to the reasoner:
      String axiom = whenClause[2];
      String interpolatedAxiom = interpolate(axiom, row);
      writelog(LogLevel.INFO, "Interpolated: \"%s\" into \"%s\"", axiom, interpolatedAxiom);

      if (!execute_query(subject, interpolatedAxiom, row, whenRuleType)) {
        // If any of the when clauses fail to be satisfied, then we do not need to evaluate any
        // of the other when clauses, or the main clause, since the main clause may only be
        // evaluated when all of the when clauses are satisfied.
        writelog(
            LogLevel.INFO,
            "When clause: \"%s %s %s\" is not satisfied.",
            subject,
            whenRuleType,
            axiom);
        return false;
      } else {
        writelog(
            LogLevel.INFO, "Validated when clause \"%s %s %s\".", subject, whenRuleType, axiom);
      }
    }
    // If we get to here, then all of the when clauses have been satisfied, so return true:
    return true;
  }

  /** INSERT DOC HERE */
  private static boolean execute_individual_query(
      OWLNamedIndividual subjectIndividual,
      OWLClassExpression ruleCE,
      List<String> row,
      String unsplitQueryType)
      throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_individual_query(): Called with parameters: "
            + "subjectIndividual: \"%s\", "
            + "ruleCE: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subjectIndividual,
        ruleCE,
        row,
        unsplitQueryType);

    // For each of the query types associated with the rule, check to see if the rule is satisfied
    // thus interpreted. If it is, then we return true, since multiple query types are interpreted
    // as a disjunction. If a query types is unrecognised, inform the user but continue on.
    String[] queryTypes = split_on_pipes(unsplitQueryType);
    for (String queryType : queryTypes) {
      if (!rule_type_recognised(queryType)) {
        writelog(
            LogLevel.ERROR,
            "Query type \"%s\" not recognised in rule \"%s\".",
            queryType,
            unsplitQueryType);
        continue;
      }

      RTypeEnum qType = query_type_to_rtenum_map.get(queryType);
      if (qType == RTypeEnum.INSTANCE || qType == RTypeEnum.DIRECT_INSTANCE) {
        NodeSet<OWLNamedIndividual> instancesFound =
            reasoner.getInstances(ruleCE, qType == RTypeEnum.DIRECT_INSTANCE);
        if (instancesFound.containsEntity(subjectIndividual)) {
          return true;
        }
      } else {
        // Spit out an error in this case but continue validating the other rules:
        writelog(
            LogLevel.ERROR,
            "%s validation not possible for OWLNamedIndividual %s.",
            qType.getRuleType(),
            subjectIndividual);
        continue;
      }
    }
    return false;
  }

  /** INSERT DOC HERE */
  private static boolean execute_class_query(
      OWLClass subjectClass, OWLClassExpression ruleCE, List<String> row, String unsplitQueryType)
      throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_class_query(): Called with parameters: "
            + "subjectClass: \"%s\", "
            + "ruleCE: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subjectClass,
        ruleCE,
        row,
        unsplitQueryType);

    // For each of the query types associated with the rule, check to see if the rule is satisfied
    // thus interpreted. If it is, then we return true, since multiple query types are interpreted
    // as a disjunction. If a query types is unrecognised, inform the user but continue on.
    String[] queryTypes = split_on_pipes(unsplitQueryType);
    for (String queryType : queryTypes) {
      if (!rule_type_recognised(queryType)) {
        writelog(
            LogLevel.ERROR,
            "Query type \"%s\" not recognised in rule \"%s\".",
            queryType,
            unsplitQueryType);
        continue;
      }

      RTypeEnum qType = query_type_to_rtenum_map.get(queryType);
      if (qType == RTypeEnum.SUB || qType == RTypeEnum.DIRECT_SUB) {
        // Check to see if the subjectClass is a (direct) subclass of the given rule:
        NodeSet<OWLClass> subClassesFound =
            reasoner.getSubClasses(ruleCE, qType == RTypeEnum.DIRECT_SUB);
        if (subClassesFound.containsEntity(subjectClass)) {
          return true;
        }
      } else if (qType == RTypeEnum.SUPER || qType == RTypeEnum.DIRECT_SUPER) {
        // Check to see if the subjectClass is a (direct) superclass of the given rule:
        NodeSet<OWLClass> superClassesFound =
            reasoner.getSuperClasses(ruleCE, qType == RTypeEnum.DIRECT_SUPER);
        if (superClassesFound.containsEntity(subjectClass)) {
          return true;
        }
      } else if (qType == RTypeEnum.EQUIV) {
        Node<OWLClass> equivClassesFound = reasoner.getEquivalentClasses(ruleCE);
        if (equivClassesFound.contains(subjectClass)) {
          return true;
        }
      } else {
        // Spit out an error in this case but continue validating the other rules:
        writelog(
            LogLevel.ERROR,
            "%s validation not possible for OWLClass %s.",
            qType.getRuleType(),
            subjectClass);
        continue;
      }
    }
    return false;
  }

  /** INSERT DOC */
  private static boolean execute_generalized_class_query(
      OWLClassExpression subjectCE,
      OWLClassExpression ruleCE,
      List<String> row,
      String unsplitQueryType)
      throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_generalized_class_query(): Called with parameters: "
            + "subjectCE: \"%s\", "
            + "ruleCE: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subjectCE,
        ruleCE,
        row,
        unsplitQueryType);

    // For each of the query types associated with the rule, check to see if the rule is satisfied
    // thus interpreted. If it is, then we return true, since multiple query types are interpreted
    // as a disjunction. If a query types is unrecognised, inform the user but continue on.
    String[] queryTypes = split_on_pipes(unsplitQueryType);
    for (String queryType : queryTypes) {
      if (!rule_type_recognised(queryType)) {
        writelog(
            LogLevel.ERROR,
            "Query type \"%s\" not recognised in rule \"%s\".",
            queryType,
            unsplitQueryType);
        continue;
      }

      RTypeEnum qType = query_type_to_rtenum_map.get(queryType);
      if (qType == RTypeEnum.SUB) {
        // Check to see if the subjectClass is a subclass of the given rule:
        OWLSubClassOfAxiom axiom = dataFactory.getOWLSubClassOfAxiom(subjectCE, ruleCE);
        if (reasoner.isEntailed(axiom)) {
          return true;
        }
      } else if (qType == RTypeEnum.SUPER) {
        // Check to see if the subjectClass is a superclass of the given rule:
        OWLSubClassOfAxiom axiom = dataFactory.getOWLSubClassOfAxiom(ruleCE, subjectCE);
        if (reasoner.isEntailed(axiom)) {
          return true;
        }
      } else if (qType == RTypeEnum.EQUIV) {
        OWLEquivalentClassesAxiom axiom =
            dataFactory.getOWLEquivalentClassesAxiom(subjectCE, ruleCE);
        if (reasoner.isEntailed(axiom)) {
          return true;
        }
      } else {
        // Spit out an error in this case but continue validating the other rules:
        writelog(
            LogLevel.ERROR,
            "%s validation not possible for OWLClassExpression %s.",
            qType.getRuleType(),
            subjectCE);
        continue;
      }
    }
    return false;
  }

  /**
   * UPDATE THIS DOC: Given an IRI, a string describing a rule to query, a list of strings
   * describing a row from the CSV, and a string representing a compound query type: Determine
   * whether, for any of the query types specified in the compound string, the IRI is in the result
   * set returned by a query of that type on the given rule. Return true if it is in one of these
   * result sets, and false if it is not.
   */
  private static boolean execute_query(
      String subject, String rule, List<String> row, String unsplitQueryType) throws Exception {

    writelog(
        LogLevel.DEBUG,
        "execute_query(): Called with parameters: "
            + "subject: \"%s\", "
            + "rule: \"%s\", "
            + "row: \"%s\", "
            + "query type: \"%s\".",
        subject,
        rule,
        row,
        unsplitQueryType);

    // Get the class expression corresponfing to the rule that has been passed:
    OWLClassExpression ruleCE = get_class_expression_from_string(rule);
    if (ruleCE == null) {
      writelog(LogLevel.ERROR, "Unable to parse rule \"%s %s\".", unsplitQueryType, rule);
      return false;
    }

    String subjectLabel = get_label_from_term(subject);
    if (subjectLabel != null) {
      // figure out if it is an instance or a class and run the appropriate query
      IRI subjectIri = label_to_iri_map.get(subjectLabel);
      OWLEntity subjectEntity = OntologyHelper.getEntity(ontology, subjectIri);
      try {
        OWLNamedIndividual subjectIndividual = subjectEntity.asOWLNamedIndividual();
        return execute_individual_query(subjectIndividual, ruleCE, row, unsplitQueryType);
      } catch (OWLRuntimeException e) {
        try {
          OWLClass subjectClass = subjectEntity.asOWLClass();
          return execute_class_query(subjectClass, ruleCE, row, unsplitQueryType);
        } catch (OWLRuntimeException ee) {
          // This actually should not happen, since if the subject has a label it should either
          // be a named class or a named individual:
          writelog(
              LogLevel.ERROR,
              "While validating \"%s\" against \"%s %s\", encountered: %s",
              subject,
              unsplitQueryType,
              rule,
              ee);
          return false;
        }
      }
    } else {
      // Try to get the class expression and run a generalised query on it:
      OWLClassExpression subjectCE = get_class_expression_from_string(subject);
      if (subjectCE == null) {
        writelog(LogLevel.ERROR, "Unable to parse subject \"%s\".", subject);
        return false;
      }
      return execute_generalized_class_query(subjectCE, ruleCE, row, unsplitQueryType);
    }
  }

  /**
   * Given a string describing a rule, a rule type of the PRESENCE type, and a string representing a
   * cell from the CSV, determine whether the cell satisfies the given presence rule: E.g. Return
   * false if the rule requires content to be present in the cell but the cell is empty, or if the
   * rule requires the cell to be empty but there is content in it.
   */
  private static void validate_presence_rule(String rule, RTypeEnum rType, String cell)
      throws IOException {

    writelog(
        LogLevel.DEBUG,
        "validate_presence_rule(): Called with parameters: "
            + "rule: \"%s\", "
            + "rule type: \"%s\", "
            + "cell: \"%s\".",
        rule,
        rType.getRuleType(),
        cell);

    // Presence-type rules (is-required, is-excluded) must be in the form of a truth value:
    if ((Arrays.asList("true", "t", "1", "yes", "y").indexOf(rule.toLowerCase()) == -1)
        && (Arrays.asList("false", "f", "0", "no", "n").indexOf(rule.toLowerCase()) == -1)) {
      writelog(
          LogLevel.ERROR,
          "Invalid rule: \"%s\" for rule type: %s. Must be one of: "
              + "true, t, 1, yes, y, false, f, 0, no, n",
          rule,
          rType.getRuleType());
      return;
    }

    // If the restriction isn't "true" then there is nothing to do. Just return:
    if (Arrays.asList("true", "t", "1", "yes", "y").indexOf(rule.toLowerCase()) == -1) {
      writelog(LogLevel.INFO, "Nothing to validate for rule: \"%s %s\"", rType.getRuleType(), rule);
      return;
    }

    switch (rType) {
      case REQUIRED:
        if (cell.trim().equals("")) {
          writeout(
              "Cell is empty but rule: \"%s %s\" does not allow this.", rType.getRuleType(), rule);
          return;
        }
        break;
      case EXCLUDED:
        if (!cell.trim().equals("")) {
          writeout(
              "Cell is non-empty (\"%s\") but rule: \"%s %s\" does not allow this.",
              cell, rType.getRuleType(), rule);
          return;
        }
        break;
      default:
        writelog(
            LogLevel.ERROR,
            "Presence validation of rule type: \"%s\" is not yet implemented.",
            rType.getRuleType());
        return;
    }
    writelog(LogLevel.INFO, "Validated rule \"%s %s\".", rType.getRuleType(), rule);
  }

  /**
   * Given a string describing a cell from the CSV, a string describing a rule to be applied against
   * that cell, a string describing the type of that rule, and a list of strings describing the row
   * containing the given cell, validate the cell, indicating any validation errors via the output
   * writer.
   */
  private static void validate_rule(String cell, String rule, List<String> row, String ruleType)
      throws Exception, IOException {

    writelog(
        LogLevel.DEBUG,
        "validate_rule(): Called with parameters: "
            + "cell: \"%s\", "
            + "rule: \"%s\", "
            + "row: \"%s\", "
            + "rule type: \"%s\".",
        cell,
        rule,
        row,
        ruleType);

    writelog(LogLevel.INFO, "Validating rule \"%s %s\" against \"%s\".", ruleType, rule, cell);
    if (!rule_type_recognised(ruleType)) {
      writelog(LogLevel.ERROR, "Unrecognised rule type \"%s\".", ruleType);
      return;
    }

    // Separate the given rule into its main clause and optional when clauses:
    SimpleEntry<String, List<String[]>> separatedRule = separate_rule(rule, ruleType);

    // Evaluate and validate any when clauses for this rule first:
    if (!validate_when_clauses(separatedRule.getValue(), row)) {
      writelog(LogLevel.INFO, "Not all when clauses have been satisfied. Not running main clause");
      return;
    }

    // Once all of the when clauses have been validated, get the RTypeEnum representation of the
    // primary rule type of this rule:
    RTypeEnum primRType = rule_type_to_rtenum_map.get(get_primary_rule_type(ruleType));

    // If the primary rule type for this rule is not in the QUERY category, process it at this step
    // and return control to the caller. The further steps below are only needed when queries are
    // going to be sent to the reasoner.
    if (primRType.getRuleCat() != RCatEnum.QUERY) {
      validate_presence_rule(separatedRule.getKey(), primRType, cell);
      return;
    }

    // If the cell contents are empty, just return to the caller silently (if the cell is not
    // expected to be empty, this will have been caught by one of the presence rules in the
    // previous step, assuming such a rule exists for the column).
    if (cell.trim().equals("")) return;

    // Interpolate the axiom that the cell will be validated against:
    String axiom = separatedRule.getKey();
    String interpolatedAxiom = interpolate(axiom, row);
    writelog(LogLevel.INFO, "Interpolated: \"%s\" into \"%s\"", axiom, interpolatedAxiom);

    // Send the query to the reasoner:
    boolean result = execute_query(cell, interpolatedAxiom, row, ruleType);
    if (!result) {
      writeout("Validation failed for rule: \"%s %s %s\".", cell, ruleType, axiom);
    } else {
      writelog(LogLevel.INFO, "Validated: \"%s %s %s\".", cell, ruleType, axiom);
    }
  }

  /**
   * Given a list of lists of strings representing the rows of a CSV, an ontology, a reasoner
   * factory, and an output writer: Extract the rules to use for validation from the CSV, create a
   * reasoner from the given reasoner factory, and then validate the CSV using those extracted
   * rules, row by row and column by column within each row, using the reasoner when required to
   * perform lookups to the ontology, indicating any validation errors via the output writer.
   */
  public static void validate(
      List<List<String>> csvData,
      OWLOntology ontology,
      OWLReasonerFactory reasonerFactory,
      Writer writer)
      throws Exception {

    // Initialize the shared variables:
    initialize(ontology, reasonerFactory, writer);

    // Extract the header and rules rows from the CSV data and map the column names to their
    // associated lists of rules:
    List<String> header = csvData.remove(0);
    List<String> allRules = csvData.remove(0);
    HashMap<String, Map<String, List<String>>> headerToRuleMap = new HashMap();
    for (int i = 0; i < header.size(); i++) {
      headerToRuleMap.put(header.get(i), parse_rules(allRules.get(i)));
    }

    // Validate the data row by row, and column by column by column within a row. csv_row_index and
    // csv_col_index are class variables that will later be used to provide information to the user
    // about the location of any errors encountered.
    for (csv_row_index = 0; csv_row_index < csvData.size(); csv_row_index++) {
      List<String> row = csvData.get(csv_row_index);
      for (csv_col_index = 0; csv_col_index < header.size(); csv_col_index++) {
        // Get the rules for the current column:
        String colName = header.get(csv_col_index);
        Map<String, List<String>> colRules = headerToRuleMap.get(colName);

        // If there are no rules for this column, then skip this cell (the cell is part of a
        // "comment" column):
        if (colRules.isEmpty()) continue;

        // Get the contents of the current cell:
        String[] cellData = split_on_pipes(row.get(csv_col_index).trim());

        // For each of the rules applicable to this column, validate the cell against it:
        for (String ruleType : colRules.keySet()) {
          for (String rule : colRules.get(ruleType)) {
            for (String data : cellData) {
              validate_rule(data, rule, row, ruleType);
            }
          }
        }
      }
    }
    tearDown();
  }
}
