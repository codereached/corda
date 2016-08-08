package com.r3corda.contracts

import com.r3corda.contracts.asset.Cash
import com.r3corda.contracts.asset.InsufficientBalanceException
import com.r3corda.contracts.asset.sumCashBy
import com.r3corda.contracts.clause.AbstractIssue
import com.r3corda.core.contracts.*
import com.r3corda.core.contracts.clauses.*
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.toStringShort
import com.r3corda.core.random63BitValue
import com.r3corda.core.utilities.Emoji
import java.security.PublicKey
import java.time.Instant
import java.util.*

/**
 * This is an ultra-trivial implementation of commercial paper, which is essentially a simpler version of a corporate
 * bond. It can be seen as a company-specific currency. A company issues CP with a particular face value, say $100,
 * but sells it for less, say $90. The paper can be redeemed for cash at a given date in the future. Thus this example
 * would have a 10% interest rate with a single repayment. Commercial paper is often rolled over (the maturity date
 * is adjusted as if the paper was redeemed and immediately repurchased, but without having to front the cash).
 *
 * This contract is not intended to realistically model CP. It is here only to act as a next step up above cash in
 * the prototyping phase. It is thus very incomplete.
 *
 * Open issues:
 *  - In this model, you cannot merge or split CP. Can you do this normally? We could model CP as a specialised form
 *    of cash, or reuse some of the cash code? Waiting on response from Ayoub and Rajar about whether CP can always
 *    be split/merged or only in secondary markets. Even if current systems can't do this, would it be a desirable
 *    feature to have anyway?
 *  - The funding steps of CP is totally ignored in this model.
 *  - No attention is paid to the existing roles of custodians, funding banks, etc.
 *  - There are regional variations on the CP concept, for instance, American CP requires a special "CUSIP number"
 *    which may need to be tracked. That, in turn, requires validation logic (there is a bean validator that knows how
 *    to do this in the Apache BVal project).
 */

val CP_PROGRAM_ID = CommercialPaper()

// TODO: Generalise the notion of an owned instrument into a superclass/supercontract. Consider composition vs inheritance.
class CommercialPaper : ClauseVerifier() {
    // TODO: should reference the content of the legal agreement, not its URI
    override val legalContractReference: SecureHash = SecureHash.sha256("https://en.wikipedia.org/wiki/Commercial_paper")

    data class Terms(
        val asset: Issued<Currency>,
        val maturityDate: Instant
    )

    override val clauses = listOf(Clauses.Group())

    override fun extractCommands(tx: TransactionForContract): List<AuthenticatedObject<CommandData>>
        = tx.commands.select<Commands>()

    data class State(
            val issuance: PartyAndReference,
            override val owner: PublicKey,
            val faceValue: Amount<Issued<Currency>>,
            val maturityDate: Instant
    ) : OwnableState, ICommercialPaperState {
        override val contract = CP_PROGRAM_ID
        override val participants: List<PublicKey>
            get() = listOf(owner)

        val token: Issued<Terms>
            get() = Issued(issuance, Terms(faceValue.token, maturityDate))

        override fun withNewOwner(newOwner: PublicKey) = Pair(Commands.Move(), copy(owner = newOwner))
        override fun toString() = "${Emoji.newspaper}CommercialPaper(of $faceValue redeemable on $maturityDate by '$issuance', owned by ${owner.toStringShort()})"

        // Although kotlin is smart enough not to need these, as we are using the ICommercialPaperState, we need to declare them explicitly for use later,
        override fun withOwner(newOwner: PublicKey): ICommercialPaperState = copy(owner = newOwner)

        override fun withIssuance(newIssuance: PartyAndReference): ICommercialPaperState = copy(issuance = newIssuance)
        override fun withFaceValue(newFaceValue: Amount<Issued<Currency>>): ICommercialPaperState = copy(faceValue = newFaceValue)
        override fun withMaturityDate(newMaturityDate: Instant): ICommercialPaperState = copy(maturityDate = newMaturityDate)
    }

    interface Clauses {
        class Group : GroupClauseVerifier<State, Issued<Terms>>() {
            override val ifNotMatched = MatchBehaviour.ERROR
            override val ifMatched = MatchBehaviour.END
            override val clauses = listOf(
                    Redeem(),
                    Move(),
                    Issue()
            )

            override fun extractGroups(tx: TransactionForContract): List<TransactionForContract.InOutGroup<State, Issued<Terms>>>
                    = tx.groupStates<State, Issued<Terms>> { it.token }
        }

        abstract class AbstractGroupClause: GroupClause<State, Issued<Terms>> {
            override val ifNotMatched = MatchBehaviour.CONTINUE
            override val ifMatched = MatchBehaviour.END
        }

        class Issue : AbstractIssue<State, Terms>(
                { map { Amount(it.faceValue.quantity, it.token) }.sumOrThrow() },
                { token -> map { Amount(it.faceValue.quantity, it.token) }.sumOrZero(token) }) {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Issue::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: Collection<AuthenticatedObject<CommandData>>,
                                token: Issued<Terms>): Set<CommandData> {
                val consumedCommands = super.verify(tx, inputs, outputs, commands, token)
                val command = commands.requireSingleCommand<Commands.Issue>()
                // If it's an issue, we can't take notary from inputs, so it must be specified in the command
                val timestamp: TimestampCommand? = tx.getTimestampBy(command.value.notary)
                val time = timestamp?.before ?: throw IllegalArgumentException("Issuances must be timestamped")

                require(outputs.all { time < it.maturityDate }) { "maturity date is not in the past" }

                return consumedCommands
            }
        }

        class Move: AbstractGroupClause() {
            override val requiredCommands: Set<Class<out CommandData>> = setOf(Commands.Move::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: Collection<AuthenticatedObject<CommandData>>,
                                token: Issued<Terms>): Set<CommandData> {
                val command = commands.requireSingleCommand<Commands.Move>()
                val input = inputs.single()
                requireThat {
                    "the transaction is signed by the owner of the CP" by (input.owner in command.signers)
                    "the state is propagated" by (outputs.size == 1)
                    // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
                    // the input ignoring the owner field due to the grouping.
                }
                return setOf(command.value)
            }
        }

        class Redeem(): AbstractGroupClause() {
            override val requiredCommands: Set<Class<out CommandData>>
                get() = setOf(Commands.Redeem::class.java)

            override fun verify(tx: TransactionForContract,
                                inputs: List<State>,
                                outputs: List<State>,
                                commands: Collection<AuthenticatedObject<CommandData>>,
                                token: Issued<Terms>): Set<CommandData> {
                // TODO: This should filter commands down to those with compatible subjects (underlying product and maturity date)
                // before requiring a single command
                val command = commands.requireSingleCommand<Commands.Redeem>()
                // If it's an issue, we can't take notary from inputs, so it must be specified in the command
                val timestamp: TimestampCommand? = tx.getTimestampBy(command.value.notary)

                val input = inputs.single()
                val received = tx.outputs.sumCashBy(input.owner)
                val time = timestamp?.after ?: throw IllegalArgumentException("Redemptions must be timestamped")
                requireThat {
                    "the paper must have matured" by (time >= input.maturityDate)
                    "the received amount equals the face value" by (received == input.faceValue)
                    "the paper must be destroyed" by outputs.isEmpty()
                    "the transaction is signed by the owner of the CP" by (input.owner in command.signers)
                }

                return setOf(command.value)
            }

        }
    }

    interface Commands : CommandData {
        class Move : TypeOnlyCommandData(), Commands
        data class Redeem(val notary: Party) : Commands
        data class Issue(val notary: Party, override val nonce: Long = random63BitValue()) : IssueCommand, Commands
    }

    /**
     * Returns a transaction that issues commercial paper, owned by the issuing parties key. Does not update
     * an existing transaction because you aren't able to issue multiple pieces of CP in a single transaction
     * at the moment: this restriction is not fundamental and may be lifted later.
     */
    fun generateIssue(issuance: PartyAndReference, faceValue: Amount<Issued<Currency>>, maturityDate: Instant, notary: Party): TransactionBuilder {
        val state = TransactionState(State(issuance, issuance.party.owningKey, faceValue, maturityDate), notary)
        return TransactionType.General.Builder(notary = notary).withItems(state, Command(Commands.Issue(notary), issuance.party.owningKey))
    }

    /**
     * Updates the given partial transaction with an input/output/command to reassign ownership of the paper.
     */
    fun generateMove(tx: TransactionBuilder, paper: StateAndRef<State>, newOwner: PublicKey) {
        tx.addInputState(paper)
        tx.addOutputState(TransactionState(paper.state.data.copy(owner = newOwner), paper.state.notary))
        tx.addCommand(Commands.Move(), paper.state.data.owner)
    }

    /**
     * Intended to be called by the issuer of some commercial paper, when an owner has notified us that they wish
     * to redeem the paper. We must therefore send enough money to the key that owns the paper to satisfy the face
     * value, and then ensure the paper is removed from the ledger.
     *
     * @throws InsufficientBalanceException if the wallet doesn't contain enough money to pay the redeemer.
     */
    @Throws(InsufficientBalanceException::class)
    fun generateRedeem(tx: TransactionBuilder, paper: StateAndRef<State>, wallet: List<StateAndRef<Cash.State>>) {
        // Add the cash movement using the states in our wallet.
        val amount = paper.state.data.faceValue.let { amount -> Amount(amount.quantity, amount.token.product) }
        Cash().generateSpend(tx, amount, paper.state.data.owner, wallet)
        tx.addInputState(paper)
        tx.addCommand(CommercialPaper.Commands.Redeem(paper.state.notary), paper.state.data.owner)
    }
}

infix fun CommercialPaper.State.`owned by`(owner: PublicKey) = copy(owner = owner)
infix fun CommercialPaper.State.`with notary`(notary: Party) = TransactionState(this, notary)
infix fun ICommercialPaperState.`owned by`(newOwner: PublicKey) = withOwner(newOwner)


