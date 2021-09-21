package recipe.lang.expressions.predicate;

import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.SettableParser;
import org.petitparser.parser.primitive.CharacterParser;
import recipe.lang.exception.*;
import recipe.lang.expressions.Expression;
import recipe.lang.expressions.TypedValue;
import recipe.lang.expressions.TypedVariable;
import recipe.lang.expressions.arithmetic.*;
import recipe.lang.store.Store;
import recipe.lang.types.Boolean;
import recipe.lang.types.Type;
import recipe.lang.utils.TypingContext;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public abstract class Condition implements Expression<Boolean> {
	static TypedValue<Boolean> TRUE;
	static TypedValue<Boolean> FALSE;

	static {
		try {
			TRUE = new TypedValue<Boolean>(Boolean.getType(), "TRUE");
			FALSE = new TypedValue<Boolean>(Boolean.getType(), "FALSE");
		} catch (MismatchingTypeException e) {
			e.printStackTrace();
		}
	}

	public static TypedValue<Boolean> getTrue(){
		return TRUE;
	}


	public static TypedValue<Boolean> getFalse(){
		return FALSE;
	}

	public boolean isSatisfiedBy(Store store) throws AttributeTypeException, AttributeNotInStoreException, MismatchingTypeException {
		TypedValue value = valueIn(store);
		if(value.getValue().equals(true)){
			return true;
		} else{
			return false;
		}
	}

	public abstract TypedValue<Boolean> valueIn(Store store) throws AttributeNotInStoreException, AttributeTypeException, MismatchingTypeException;
	public abstract Expression<Boolean> close(Store store, Set<String> CV) throws AttributeNotInStoreException, AttributeTypeException, MismatchingTypeException, TypeCreationException, RelabellingTypeException;

	public static Parser typeParser(TypingContext context) throws Exception {
		return Condition.parser(context);
	}

	public static org.petitparser.parser.Parser parser(TypingContext context) throws Exception {
		org.petitparser.parser.Parser arithmeticExpression = ArithmeticExpression.typeParser(context);

		SettableParser parser = SettableParser.undefined();
		SettableParser basic = SettableParser.undefined();

		org.petitparser.parser.Parser nonBooleanExpressions = arithmeticExpression.or(context.getComplementSubContext(Boolean.getType()).variableParser()).or(context.getComplementSubContext(Boolean.getType()).valueParser());

		org.petitparser.parser.Parser and = And.parser(basic);
		org.petitparser.parser.Parser implies = Implies.parser(basic);
		org.petitparser.parser.Parser or = Or.parser(basic);
		org.petitparser.parser.Parser not = Not.parser(basic);

		org.petitparser.parser.Parser value = Boolean.getType().valueParser();
		org.petitparser.parser.Parser variable = context.getSubContext(Boolean.getType()).variableParser();
		org.petitparser.parser.Parser guardReference = GuardReference.parser(context, basic);


		org.petitparser.parser.Parser nonBooleanIsEqualTo = IsEqualTo.parser(nonBooleanExpressions);
		org.petitparser.parser.Parser nonBooleanIsNotEqualTo = IsNotEqualTo.parser(nonBooleanExpressions);

		org.petitparser.parser.Parser isLessThan = IsLessThan.parser(arithmeticExpression);
		org.petitparser.parser.Parser isLessOrEqualThan = IsLessOrEqualThan.parser(arithmeticExpression);
		org.petitparser.parser.Parser isGreaterOrEqualThan = IsGreaterOrEqualThan.parser(arithmeticExpression);
		org.petitparser.parser.Parser isGreaterThan = IsGreaterThan.parser(arithmeticExpression);

		org.petitparser.parser.Parser nonBooleanComparators =
				nonBooleanIsEqualTo
						.or(nonBooleanIsNotEqualTo)
						.or(isLessThan)
						.or(isLessOrEqualThan)
						.or(isGreaterOrEqualThan)
						.or(isGreaterThan);

		SettableParser bracketedParser = SettableParser.undefined();

		org.petitparser.parser.Parser comparatorExpressions = value.or(variable).or(guardReference).or(CharacterParser.of('(').seq(bracketedParser).seq(CharacterParser.of(')')));

		org.petitparser.parser.Parser booleanIsEqualTo = IsEqualTo.parser(comparatorExpressions);
		org.petitparser.parser.Parser booleanIsNotEqualTo = IsNotEqualTo.parser(comparatorExpressions);

		org.petitparser.parser.Parser booleanComparators =
				booleanIsEqualTo
						.or(booleanIsNotEqualTo);

		parser.set(and
				.or(or)
				.or(implies)
				.or(basic));

		basic.set(booleanComparators
				.or(nonBooleanComparators)
				.or(value)
				.or(variable)
				.or(guardReference)
				.or(not)
				.or(CharacterParser.of('(').trim().seq(parser).seq(CharacterParser.of(')'))
						.map((List<Object> values) -> {
							return values.get(1);
						}))
		);

		bracketedParser.set(CharacterParser.of('(').trim().seq(parser).seq(CharacterParser.of(')'))
				.map((List<Object> values) -> {
					return values.get(1);
				}));


		return parser;
	}

	public abstract Condition relabel(Function<TypedVariable, Expression> relabelling) throws RelabellingTypeException, MismatchingTypeException;

	@Override
	public int hashCode(){
		return Objects.hash(this.toString());
	}

	@Override
	public java.lang.Boolean isValidAssignmentFor(TypedVariable var){
		return var.getType().equals(Boolean.getType());
	}

	@Override
	public Type getType(){
		return Boolean.getType();
	}
}
