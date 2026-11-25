package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.on.ebx.factory.mappingmatrix.MatrixCounter;
import com.on.ebx.factory.mappingmatrix.MatrixHtml;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.mappingmatrix.MatrixQueueFixtureTest;

/**
 * Staging polish (2026-10-17, docs/UX-STAGING-POLISH.md sec 3): the numbers step 4 of the wizard quotes under "Review the proposal" are the numbers the Mapping
 * matrix draws, on the SAME project. Both screens read one {@link MatrixCounter.Tally}; this test renders the matrix (header, footer count, footer button) and
 * the wizard (through {@code Model#matrixFigures}, the one way the service fills the model) and compares the figures that come out of the two HTML pages -
 * not the figures that go in. Step 4's own tally ({@link Step4Counts#waiting}, which also counts cleaning steps and look-ups) is shown to differ and to play
 * no part. JUnit-free: run the main, a failure throws.
 */
public final class Step4MatrixCountsTest
{
	private static final Pattern HEADER_ATTENTION = Pattern.compile("(\\d+) points? needs? your attention");
	private static final Pattern HEADER_ONLY_BUTTON = Pattern.compile("Nothing needs your attention; (\\d+) items? (?:goes|go) with the button");
	private static final Pattern FOOTER_NEED_YOU = Pattern.compile("(\\d+) items? needs? you<");
	private static final Pattern FOOTER_BUTTON = Pattern.compile("Accept the (\\d+) proposals at");
	private static final Pattern STEP4_FLAGGED = Pattern.compile("(\\d+) points? needs? your attention(?:, the other (\\d+) go with one click)?");

	public static void main(final String[] pArgs)
	{
		final List<MatrixModel.Project> fixtures = new ArrayList<MatrixModel.Project>();
		fixtures.add(WizardC3StepsTest.uxCrmSf2Shape()); // 17 flagged, 99 with the button
		fixtures.add(MatrixQueueFixtureTest.persVol9Like(true, false)); // the PersVol9-like project: eight items, three below the bar
		fixtures.add(MatrixQueueFixtureTest.persVol9Like(false, false)); // everything still open
		fixtures.add(Step4MatrixCountsTest.noFlag(40)); // nothing flagged: the button takes it all
		int figuresCompared = 0;
		for (final MatrixModel.Project project : fixtures)
		{
			figuresCompared += Step4MatrixCountsTest.compare(project);
		}
		check(figuresCompared >= 6, "the comparison really compared figures: " + figuresCompared);
		Step4MatrixCountsTest.nothingLeftInTheMatrixSaysNothing();
		System.out.println("Step4MatrixCountsTest OK (" + figuresCompared + " figures compared)");
	}

	/** Returns how many pairs of figures were compared. */
	private static int compare(final MatrixModel.Project pProject)
	{
		final MatrixCounter.Tally tally = MatrixCounter.of(pProject);
		final MatrixHtml.State state = new MatrixHtml.State();
		state.activeEntityId = pProject.entities.get(0).id;
		final String matrix = MatrixHtml.render(pProject, state);

		final WizardSnapshot snapshot = WizardRulesTest.retSnapshot();
		final WizardPage.Model model = WizardC3StepsTest.model(snapshot, WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix?o=" + state.activeEntityId;
		model.matrixFigures(tally);
		final String step4 = WizardPageTest.section(WizardPage.render(model), 4);
		final Matcher note = Pattern.compile("<b class=\"mdmf-review-note\">([^<]*)</b>").matcher(step4);
		final String sentence = note.find() ? note.group(1) : "";
		final String where = " (needYou " + tally.needYou + ", acceptAll " + tally.acceptAll + ", withButton " + tally.withButton + ")";

		// the button of the matrix: its N is the "others" of step 4 and the "only the button" figure of the header
		final Matcher button = FOOTER_BUTTON.matcher(matrix);
		final int buttonN = button.find() ? Integer.parseInt(button.group(1)) : 0;
		check(buttonN == tally.acceptAll, "the footer button draws acceptAll" + where);
		int compared = 1;
		if (tally.needYou > 0)
		{
			final Matcher header = HEADER_ATTENTION.matcher(matrix);
			check(header.find() && Integer.parseInt(header.group(1)) == tally.needYou, "the header draws needYou" + where);
			final int headerN = Integer.parseInt(header.group(1));
			final Matcher footer = FOOTER_NEED_YOU.matcher(matrix);
			check(footer.find() && Integer.parseInt(footer.group(1)) == tally.needYou, "the footer count draws needYou" + where);
			final Matcher line = STEP4_FLAGGED.matcher(sentence);
			check(line.find(), "step 4 says how many points need you: '" + sentence + "'" + where);
			check(Integer.parseInt(line.group(1)) == headerN && headerN == Integer.parseInt(footer.group(1)), "step 4, the header and the footer: the same points" + where);
			if (tally.acceptAll > 0)
			{
				check(line.group(2) != null && Integer.parseInt(line.group(2)) == buttonN, "step 4's 'the other N' is the footer button's N: '" + sentence + "'" + where);
				compared++;
			}
			else
			{
				check(line.group(2) == null, "nothing goes with the button: step 4 says no 'other'" + where);
			}
			compared += 2;
		}
		else if (tally.acceptAll > 0)
		{
			final Matcher header = HEADER_ONLY_BUTTON.matcher(matrix);
			check(header.find() && Integer.parseInt(header.group(1)) == tally.acceptAll, "the header draws the button's figure" + where);
			check(sentence.equals("Nothing flagged: one click accepts everything"), "step 4 agrees nothing is flagged: '" + sentence + "'" + where);
			compared++;
		}
		// step 4's own tally (it also counts cleaning steps and look-ups) plays no part: decide one of its proposals and the sentence stays the matrix' own
		final int waitingBefore = Step4Counts.of(snapshot).waiting();
		for (final WizardSnapshot.StagingEntityInfo entity : snapshot.stagingEntities)
		{
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				if ("PROPOSED".equals(attribute.status) && waitingBefore == Step4Counts.of(snapshot).waiting())
				{
					attribute.status = "ACCEPTED";
				}
			}
		}
		if (Step4Counts.of(snapshot).waiting() > 0)
		{
			check(Step4Counts.of(snapshot).waiting() < waitingBefore, "the wizard's own tally moved");
			final Matcher again = Pattern.compile("<b class=\"mdmf-review-note\">([^<]*)</b>").matcher(WizardPageTest.section(WizardPage.render(model), 4));
			check(again.find() ? again.group(1).equals(sentence) : sentence.isEmpty(), "and the sentence did not follow it" + where);
		}
		return compared;
	}

	private static void nothingLeftInTheMatrixSaysNothing()
	{
		final MatrixModel.Project done = Step4MatrixCountsTest.noFlag(0);
		final MatrixCounter.Tally tally = MatrixCounter.of(done);
		check(tally.needYou == 0 && tally.acceptAll == 0 && tally.allDone(), "the fixture has nothing left");
		final WizardPage.Model model = WizardC3StepsTest.model(WizardRulesTest.retSnapshot(), WizardRules.STEP_STAGING);
		model.proposalRunExists = true;
		model.proposalDoneMatrixUrl = "/matrix";
		model.matrixFigures(tally);
		check(!WizardPageTest.section(WizardPage.render(model), 4).contains("mdmf-review-note\""), "nothing left in the matrix: no 'one click accepts everything' line from step 4");
	}

	/** One object, {@code pProposed} fields still PROPOSED with a high confidence and no flag; the rest accepted. */
	private static MatrixModel.Project noFlag(final int pProposed)
	{
		final List<MatrixModel.Attr> attrs = new ArrayList<MatrixModel.Attr>();
		final Map<String, MatrixModel.Cell> cells = new LinkedHashMap<String, MatrixModel.Cell>();
		for (int i = 0; i < Math.max(pProposed, 3); i++)
		{
			final boolean open = i < pProposed;
			attrs.add(new MatrixModel.Attr("a" + i, "f" + i, "STRING", false, false, false, open ? "PROPOSED" : "ACCEPTED", Integer.valueOf(95)));
		}
		final MatrixModel.Feed feed = new MatrixModel.Feed("f1", "s1", "t1", "T", "ACCEPTED", null, "", false, null, cells, Integer.valueOf(95), "");
		return new MatrixModel.Project("P", new ArrayList<MatrixModel.Source>(Arrays.asList(new MatrixModel.Source("s1", "SAP", "#000"))),
			new ArrayList<MatrixModel.Entity>(Arrays.asList(new MatrixModel.Entity("e1", "P", attrs, Arrays.asList(feed), "ACCEPTED", Integer.valueOf(100)))));
	}

	private static void check(final boolean pCondition, final String pWhat)
	{
		if (!pCondition)
		{
			throw new AssertionError("Step4MatrixCountsTest: " + pWhat);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		Step4MatrixCountsTest.check(pCondition, pWhat);
	}
}
