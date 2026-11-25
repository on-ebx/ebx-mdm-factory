package com.on.ebx.factory.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.info.SchemaFacetTableRef;

/**
 * A schema stand-in for the writer-guard tests (named ...Test so that tools/test_local.sh compiles it with the tests; its main checks the stand-ins themselves): {@link SchemaNode} proxies answering only what {@link WriterShapes#kindOf} asks (table, occurrences, table
 * reference facet, complex, Java class), held by absolute path - no EBX repository needed. Test support, not a test.
 */
public final class FakeSchemaTest
{
	private FakeSchemaTest()
	{
	}

	/** The stand-ins answer what the guard asks of them (a sanity check of the test support itself). */
	public static void main(final String[] pArgs)
	{
		final Map<String, SchemaNode> nodes = FakeSchemaTest.map();
		nodes.put("/a", FakeSchemaTest.node(Kind.TABLE));
		final SchemaNode root = FakeSchemaTest.root(nodes);
		if (root.getNode(Path.parse("/a")) == null || root.getNode(Path.parse("/b")) != null)
		{
			throw new AssertionError("the stand-in root answers the registered path only");
		}
		for (final Kind kind : Kind.values())
		{
			if (kind != Kind.OTHER && WriterShapes.kindOf(FakeSchemaTest.node(kind)) != kind)
			{
				throw new AssertionError("a " + kind + " stand-in must read back as " + kind);
			}
		}
		System.out.println("FakeSchemaTest OK");
	}

	/** A schema root whose {@code getNode(path)} answers the node registered at that exact path, or null. */
	public static SchemaNode root(final Map<String, SchemaNode> pNodesByPath)
	{
		return FakeSchemaTest.proxy(new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				if ("getNode".equals(pMethod.getName()) && pArgs != null && pArgs.length == 1 && pArgs[0] instanceof Path)
				{
					return pNodesByPath.get(((Path) pArgs[0]).format());
				}
				return FakeSchemaTest.defaultAnswer(pMethod);
			}
		});
	}

	/** A schema root with one node per expectation, of exactly the kind it expects ({@link Kind#OTHER} becomes a date-like terminal). */
	public static SchemaNode satisfying(final List<Expectation> pExpected)
	{
		final Map<String, SchemaNode> nodes = new LinkedHashMap<String, SchemaNode>();
		for (final Expectation expectation : pExpected)
		{
			nodes.put(expectation.path, FakeSchemaTest.node(expectation.kind));
		}
		return FakeSchemaTest.root(nodes);
	}

	/** The same, minus one path. */
	public static SchemaNode satisfyingWithout(final List<Expectation> pExpected, final String pMissingPath)
	{
		final Map<String, SchemaNode> nodes = new LinkedHashMap<String, SchemaNode>();
		for (final Expectation expectation : pExpected)
		{
			if (!expectation.path.equals(pMissingPath))
			{
				nodes.put(expectation.path, FakeSchemaTest.node(expectation.kind));
			}
		}
		return FakeSchemaTest.root(nodes);
	}

	/** One node of the given kind. */
	public static SchemaNode node(final Kind pKind)
	{
		final boolean table = pKind == Kind.TABLE;
		final int max = table || pKind == Kind.LIST ? Integer.MAX_VALUE : 1;
		final boolean complex = pKind == Kind.GROUP;
		final Class<?> javaClass;
		switch (pKind)
		{
		case STRING:
			javaClass = String.class;
			break;
		case INT:
			javaClass = Integer.class;
			break;
		case DECIMAL:
			javaClass = BigDecimal.class;
			break;
		case BOOLEAN:
			javaClass = Boolean.class;
			break;
		case LOCALE:
			javaClass = Locale.class;
			break;
		default:
			javaClass = java.util.Date.class;
			break;
		}
		final SchemaFacetTableRef reference = pKind == Kind.FOREIGN_KEY ? FakeSchemaTest.<SchemaFacetTableRef> stub(SchemaFacetTableRef.class) : null;
		return FakeSchemaTest.proxy(new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				switch (pMethod.getName())
				{
				case "isTableNode":
					return Boolean.valueOf(table);
				case "getMaxOccurs":
					return Integer.valueOf(max);
				case "isComplex":
					return Boolean.valueOf(complex);
				case "getFacetOnTableReference":
					return reference;
				case "getOccurrencesJavaClass":
					return javaClass;
				default:
					return FakeSchemaTest.defaultAnswer(pMethod);
				}
			}
		});
	}

	/** A node whose every question throws: a schema API that does not answer. */
	public static SchemaNode brokenRoot()
	{
		return FakeSchemaTest.proxy(new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				throw new IllegalStateException("the schema does not answer");
			}
		});
	}

	@SuppressWarnings("unchecked")
	static <T> T stub(final Class<T> pInterface)
	{
		return (T) Proxy.newProxyInstance(pInterface.getClassLoader(), new Class<?>[] { pInterface }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pArgs)
			{
				return FakeSchemaTest.defaultAnswer(pMethod);
			}
		});
	}

	private static SchemaNode proxy(final InvocationHandler pHandler)
	{
		return (SchemaNode) Proxy.newProxyInstance(SchemaNode.class.getClassLoader(), new Class<?>[] { SchemaNode.class }, pHandler);
	}

	static Object defaultAnswer(final Method pMethod)
	{
		final Class<?> type = pMethod.getReturnType();
		if (type == boolean.class)
		{
			return Boolean.FALSE;
		}
		if (type == int.class)
		{
			return Integer.valueOf(0);
		}
		if (type == long.class)
		{
			return Long.valueOf(0L);
		}
		return null;
	}

	/** Registered nodes by path, for a hand-built schema. */
	public static Map<String, SchemaNode> map()
	{
		return new HashMap<String, SchemaNode>();
	}
}
