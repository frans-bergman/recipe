package recipe.lang.utils;

import org.petitparser.parser.Parser;
import org.petitparser.parser.primitive.CharacterParser;
import org.petitparser.parser.primitive.StringParser;
import recipe.lang.expressions.Expression;
import recipe.lang.expressions.TypedValue;
import recipe.lang.expressions.TypedVariable;
import recipe.lang.expressions.arithmetic.ArithmeticExpression;
import recipe.lang.expressions.arithmetic.NumberVariable;
import recipe.lang.expressions.channels.ChannelExpression;
import recipe.lang.expressions.channels.ChannelValue;
import recipe.lang.expressions.channels.ChannelVariable;
import recipe.lang.expressions.predicate.BooleanVariable;
import recipe.lang.expressions.predicate.Condition;
import recipe.lang.expressions.strings.StringExpression;
import recipe.lang.expressions.strings.StringVariable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.petitparser.parser.primitive.CharacterParser.*;

public class Parsing {
    public static org.petitparser.parser.Parser disjunctiveWordParser(Set<String> allowed, Function<String, Expression> transformation) {
        return disjunctiveWordParser(new ArrayList<>(allowed), transformation);
    }

    public static org.petitparser.parser.Parser disjunctiveWordParser(List<String> allowed, Function<String, Expression> transformation) {
        if (allowed.size() == 0) {
            return StringParser.of("").not();
        }

        org.petitparser.parser.Parser parser = StringParser.of(allowed.get(0));
        for (int i = 1; i < allowed.size(); i++) {
            parser = parser.or(StringParser.of(allowed.get(i)));
        }

        parser = (parser).seq(CharacterParser.word().not()).map((List<Object> values) -> {
            return transformation.apply((String) values.get(0));
        });

        return parser;
    }

    public static Parser eof(){
        return any().not();
    }

    public static org.petitparser.parser.Parser expressionParser(TypingContext context) {
        return Condition.parser(context)
                .or(ArithmeticExpression.parser(context))
                .or(StringExpression.parser(context))
                .or(ChannelExpression.parser(context));
    }

    public static org.petitparser.parser.Parser variableParser(TypingContext context) {
        return BooleanVariable.parser(context)
                .or(NumberVariable.parser(context))
                .or(StringVariable.parser(context))
                .or(ChannelVariable.parser(context));
    }


    public static org.petitparser.parser.Parser assignmentListParser(TypingContext variableContext,
                                                                     TypingContext expressionContext) {
        Parser assignment =
                variableParser(variableContext)
                        .seq(StringParser.of(":=").trim())
                        .seq(expressionParser(expressionContext))
                        .map((List<Object> values) -> {
                            return new Pair(values.get(0).toString(), values.get(2));
                        });

        Parser assignmentList =
                assignment
                        .delimitedBy(CharacterParser.of(',').trim())
                        .map((List<Object> values) -> {
                            HashMap<String, Expression> map = new HashMap();
                            for(Object v : values){
                                if(v.getClass().equals(Character.class)) continue;
                                Pair<String, Expression> pair = (Pair<String, Expression>) v;
                                map.put(pair.getLeft(), pair.getRight());
                            }

                            return map;
                        });

        return assignmentList;
    }

    public static Parser typedVariableList(){
        org.petitparser.parser.Parser stringParser = word().plus().seq(CharacterParser.word().not()).flatten().trim();

        org.petitparser.parser.Parser numberVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("integer")
                        .or(StringParser.of("Integer"))
                        .or(StringParser.of("Int"))
                        .or(StringParser.of("int")).trim())
                .map((List<Object> values) -> {
                    return new NumberVariable((String) values.get(0));
                });

        org.petitparser.parser.Parser stringVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("string")
                        .or(StringParser.of("String")).trim())
                .map((List<Object> values) -> {
                    return new StringVariable((String) values.get(0));
                });
        org.petitparser.parser.Parser boolVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("boolean")
                        .or(StringParser.of("Boolean"))
                        .or(StringParser.of("bool"))
                        .or(StringParser.of("Bool")).trim())
                .map((List<Object> values) -> {
                    return new BooleanVariable((String) values.get(0));
                });
        org.petitparser.parser.Parser channelVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("channel")
                        .or(StringParser.of("Channel"))
                        .or(StringParser.of("chan"))
                        .or(StringParser.of("Chan")).trim())
                .map((List<Object> values) -> {
                    return new ChannelVariable((String) values.get(0));
                });

        org.petitparser.parser.Parser typedVariable = numberVarParser.or(boolVarParser).or(stringVarParser).or(channelVarParser);
        org.petitparser.parser.Parser typedVariableList = (typedVariable.separatedBy(CharacterParser.of(',').trim()))
                .map((List<Object> values) -> {
                    List<Object> delimitedTypedVariables = values;
                    Map<String, TypedVariable> typedVariables = new HashMap<>();
                    for (int i = 0; i < delimitedTypedVariables.size(); i += 2) {
                        TypedVariable typedVar = (TypedVariable) delimitedTypedVariables.get(i);
                        typedVariables.put(typedVar.getName(), typedVar);
                    }
                    return typedVariables;
                });

        return typedVariableList;
    }

    public static Parser typedAssignmentList(TypingContext channelValueContext){
        org.petitparser.parser.Parser stringParser = (word().plus().seq(CharacterParser.word().not())).flatten().trim();

        org.petitparser.parser.Parser numberVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("int").trim())
                .seq(StringParser.of(":=").trim())
                .seq(ArithmeticExpression.typeParser(new TypingContext()))
                .map((List<Object> values) -> {
                    return new Pair(new NumberVariable((String) values.get(0)), values.get(4));
                });

        org.petitparser.parser.Parser stringVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("string").trim())
                .seq(StringParser.of(":=").trim())
                .seq(StringExpression.typeParser(new TypingContext()))
                .map((List<Object> values) -> {
                    return new Pair(new StringVariable((String) values.get(0)), values.get(4));
                });
        org.petitparser.parser.Parser boolVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("bool").trim())
                .seq(StringParser.of(":=").trim())
                .seq(Condition.typeParser(channelValueContext))
                .map((List<Object> values) -> {
                    return new Pair(new BooleanVariable((String) values.get(0)), values.get(4));
                });

        org.petitparser.parser.Parser channelVarParser = stringParser
                .seq(CharacterParser.of(':').trim())
                .seq(StringParser.of("channel").trim())
                .seq(StringParser.of(":=").trim())
                .seq(ChannelExpression.typeParser(channelValueContext))
                .map((List<Object> values) -> {
                    return new Pair(new ChannelVariable((String) values.get(0)), values.get(4));
                });

        org.petitparser.parser.Parser typedVariableAssignment = numberVarParser.or(boolVarParser).or(stringVarParser).or(channelVarParser);
        org.petitparser.parser.Parser typedVariableAssignmentList = (typedVariableAssignment.delimitedBy(CharacterParser.of('\n')))
                .map((List<Object> values) -> {
                    List<Object> delimitedTypedVariablesAssignment = values;
                    Map<String, TypedVariable> typedVariables = new HashMap<>();
                    Map<String, Expression> typedVariableValues = new HashMap<>();
                    for (int i = 0; i < delimitedTypedVariablesAssignment.size(); i += 2) {
                        Pair<TypedVariable, Expression> varVal = ((Pair<TypedVariable, Expression>) delimitedTypedVariablesAssignment.get(i));
                        typedVariables.put(varVal.getLeft().getName(), varVal.getLeft());
                        typedVariableValues.put(varVal.getLeft().getName(), varVal.getRight());
                    }
                    return new Pair(typedVariables, typedVariableValues);
                });

        return typedVariableAssignmentList;
    }

    public static Parser guardDefinitionList(){
        AtomicReference<TypingContext> typedVariableList = new AtomicReference<>(new TypingContext());
        org.petitparser.parser.Parser guardDefinitionParser = StringParser.of("guard").trim()
                .seq(word().plus().trim().flatten())
                .seq(CharacterParser.of('(').trim())
                .seq(typedVariableList()
                        .mapWithSideEffects((Map<String, Expression> value) -> {
                            typedVariableList.get().setAll(new TypingContext(value));
                            return value;
                        }))
                .seq(CharacterParser.of(')').trim())
                .seq(StringParser.of(":=").trim())
                .seq(new LazyParser<>((TypingContext context) -> Condition.parser(context), typedVariableList.get()))
                .map((List<Object> values) -> {
                    return new HashMap.SimpleEntry<>(values.get(1), new Pair(values.get(3), values.get(6)));
                });

        org.petitparser.parser.Parser guardDefinitionListParser = guardDefinitionParser.separatedBy(CharacterParser.of('\n').plus())
                .map((List<Object> values) -> {
                    Map<String, Map<String, Expression>> guardsParams = new HashMap();
                    Map<String, Condition> guards = new HashMap();
                    for(Object v : values){
                        HashMap.SimpleEntry entry = (HashMap.SimpleEntry) v;
                        guardsParams.put((String) entry.getKey(), (Map<String, Expression>) ((Pair) entry.getValue()).getLeft());
                        guards.put((String) entry.getKey(), (Condition) ((Pair) entry.getValue()).getRight());
                    }
                    return new Pair(guardsParams, guards);
                });

        return guardDefinitionListParser;
    }

    public static Parser channelValues(){
        Parser parser = word().plus().flatten().delimitedBy(CharacterParser.of(' ').plus().or(CharacterParser.of(',')))
                .map((List<Object> values) -> {
                    List<ChannelValue> vals = new ArrayList<>();

                    for(Object v : values){
                        if(!v.getClass().equals(Character.class)){
                            vals.add(new ChannelValue((String) v));
                        }
                    }
                    return vals;
                });

        return parser;
    }

    public static Parser labelledParser(String label, Parser parser){
        return StringParser.of(label).trim()
                .seq(CharacterParser.of(':').trim())
                .seq(parser.trim())
                .map((List<Object> values) -> {
                    return values.get(2);
                });
    }

    public static Parser conditionalFail(Boolean yes){
        if(yes){
            return StringParser.of("").not();
        } else{
            return StringParser.of("");
        }
    }

    public static Parser relabellingParser(TypingContext localContext, TypingContext communicationContext){
        return labelledParser("relabel", (Parsing.expressionParser(communicationContext).trim()
                .seq(StringParser.of("<-").trim())
                .seq(Parsing.expressionParser(localContext)).delimitedBy(CharacterParser.of('\n'))
                )).map((List<Object> values) -> {
                    values.removeIf(v -> v.equals('\n'));
                    Map<TypedVariable, Expression> relabellingMap = new HashMap<>();
                    for(Object relabelObj : values){
                        List relabel = (List) relabelObj;
                        relabellingMap.put((TypedVariable) relabel.get(0), (Expression) relabel.get(2));
                    }
                    return relabellingMap;
                });
    }

    public static Parser receiveGuardParser(TypingContext localContext, TypingContext channelContext){
        TypingContext receiveGuardContext = TypingContext.union(channelContext, localContext);
        receiveGuardContext.set("channel", new ChannelVariable("channel"));

        return labelledParser("receive-guard", Condition.parser(receiveGuardContext))
                .map((Condition cond) -> {
                    return cond;
                });
    }
}