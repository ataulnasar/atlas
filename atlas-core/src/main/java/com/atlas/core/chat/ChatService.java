package com.atlas.core.chat;

import com.atlas.core.document.ChunkBody;
import com.atlas.core.document.ChunkContentService;
import com.atlas.core.document.Citation;
import com.atlas.core.document.HybridSearchHit;
import com.atlas.core.document.HybridSearchService;
import com.atlas.core.document.SearchFilter;
import com.atlas.core.embedding.EmbeddingService;
import com.atlas.core.generation.AssembledContext;
import com.atlas.core.generation.ChatGenerator;
import com.atlas.core.generation.CitationExtraction;
import com.atlas.core.generation.CitationExtractor;
import com.atlas.core.generation.ContextAssembler;
import com.atlas.core.generation.GenerationProperties;
import com.atlas.core.generation.GenerationResult;
import com.atlas.core.generation.PromptTemplate;
import com.atlas.core.generation.QaPair;
import com.atlas.core.generation.RetrievedChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The RAG loop behind {@code POST /api/chat}: resolve the conversation and its recent history,
 * retrieve context for the current question, assemble the grounded prompt, generate, reconcile
 * citations, and persist the turn.
 *
 * <p>Retrieval degrades to keyword-only when no embedding provider is present (mirroring the search
 * endpoints), but generation does not degrade — the caller passes a non-null {@link ChatGenerator}
 * (the controller returns 503 when there is none). The user and assistant messages are persisted
 * only after a successful generation, so a provider failure leaves nothing behind.
 */
@Service
class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);

  private static final int DEFAULT_TOP_K = 8;
  private static final int MAX_TOP_K = 50;

  static final String RETRIEVAL_MODE_HYBRID = "hybrid";
  static final String RETRIEVAL_MODE_KEYWORD = "keyword";

  private final ConversationRepository conversationRepository;
  private final MessageRepository messageRepository;
  private final TurnPersistence turnPersistence;
  private final HybridSearchService hybridSearchService;
  private final ChunkContentService chunkContentService;
  private final ContextAssembler contextAssembler;
  private final PromptTemplate promptTemplate;
  private final CitationExtractor citationExtractor;
  private final GenerationProperties generationProperties;

  ChatService(
      ConversationRepository conversationRepository,
      MessageRepository messageRepository,
      TurnPersistence turnPersistence,
      HybridSearchService hybridSearchService,
      ChunkContentService chunkContentService,
      ContextAssembler contextAssembler,
      PromptTemplate promptTemplate,
      CitationExtractor citationExtractor,
      GenerationProperties generationProperties) {
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.turnPersistence = turnPersistence;
    this.hybridSearchService = hybridSearchService;
    this.chunkContentService = chunkContentService;
    this.contextAssembler = contextAssembler;
    this.promptTemplate = promptTemplate;
    this.citationExtractor = citationExtractor;
    this.generationProperties = generationProperties;
  }

  /**
   * Runs the full loop and returns the response, generating in one blocking call. {@code
   * embeddingService} may be null (keyless retrieval); {@code generator} is always non-null — the
   * controller enforces that.
   *
   * @throws ConversationNotFoundException if a supplied conversationId doesn't exist
   * @throws com.atlas.core.generation.GenerationException if the provider call fails (→ 502)
   */
  ChatResponse chat(
      ChatRequest request, EmbeddingService embeddingService, ChatGenerator generator) {
    RagContext context = prepare(request, embeddingService);
    long generateStart = System.nanoTime();
    GenerationResult generation = generator.generate(context.systemPrompt(), context.userPrompt());
    return finish(context, generation, System.nanoTime() - generateStart);
  }

  /**
   * The same RAG loop as {@link #chat}, but generation streams: each text delta is delivered to
   * {@code onToken} as it arrives (raw, with the model's original [cN] markers), and the fully
   * reconciled {@link ChatResponse} — renumbered answer, cited-subset citations, usage — is
   * returned once the answer is complete. Retrieval, assembly, citation reconciliation and
   * persistence are the identical shared code ({@link #prepare} / {@link #finish}); only the
   * generation call and the delivery differ, so the two endpoints cannot drift apart. Persistence
   * happens once, in {@link #finish}, after the whole answer has assembled — a mid-stream failure
   * throws before {@code finish} and persists nothing.
   *
   * @throws ConversationNotFoundException if a supplied conversationId doesn't exist
   * @throws com.atlas.core.generation.GenerationException if the provider call fails mid-stream
   */
  ChatResponse chatStreaming(
      ChatRequest request,
      EmbeddingService embeddingService,
      ChatGenerator generator,
      java.util.function.Consumer<String> onToken) {
    RagContext context = prepare(request, embeddingService);
    long generateStart = System.nanoTime();
    GenerationResult generation =
        generator.generateStreaming(context.systemPrompt(), context.userPrompt(), onToken);
    return finish(context, generation, System.nanoTime() - generateStart);
  }

  // Everything up to (but not including) generation: resolve the conversation + history, retrieve,
  // assemble the prompt. Shared verbatim by the sync and streaming paths.
  private RagContext prepare(ChatRequest request, EmbeddingService embeddingService) {
    String question = request.question().strip();

    // Resolve conversation + load history (a new conversation has none, and is created lazily in
    // the persist step so a generation failure leaves no orphan row).
    UUID existingConversationId = request.conversationId();
    List<QaPair> history;
    if (existingConversationId != null) {
      if (!conversationRepository.exists(existingConversationId)) {
        throw new ConversationNotFoundException(existingConversationId);
      }
      history = loadHistory(existingConversationId);
    } else {
      history = List.of();
    }

    // Retrieve on the current question only.
    long retrieveStart = System.nanoTime();
    int topK = normalizeTopK(request.topK());
    List<HybridSearchHit> hits =
        hybridSearchService.search(question, topK, SearchFilter.NONE, embeddingService);
    String retrievalMode =
        embeddingService != null ? RETRIEVAL_MODE_HYBRID : RETRIEVAL_MODE_KEYWORD;
    List<RetrievedChunk> retrievedChunks = toRetrievedChunks(hits);
    long retrieveNanos = System.nanoTime() - retrieveStart;

    // Assemble context + prompt.
    long assembleStart = System.nanoTime();
    AssembledContext context = contextAssembler.assemble(retrievedChunks);
    String systemPrompt = promptTemplate.system();
    String userPrompt = promptTemplate.user(history, context.sources(), question);
    long assembleNanos = System.nanoTime() - assembleStart;

    return new RagContext(
        existingConversationId,
        question,
        retrievalMode,
        hits.size(),
        context.citations(),
        systemPrompt,
        userPrompt,
        retrieveNanos,
        assembleNanos);
  }

  // Everything after generation: reconcile citations against the offered set, persist the turn in
  // one transaction (only now that we have an answer), and build the response. Shared verbatim.
  private ChatResponse finish(RagContext context, GenerationResult generation, long generateNanos) {
    CitationExtraction extraction =
        citationExtractor.extract(generation.text(), context.offeredCitations());
    String answer = extraction.answer();
    List<Citation> citations = extraction.citations();

    UUID conversationId =
        turnPersistence.persistTurn(
            context.existingConversationId(), context.question(), answer, citations);

    ChatUsage usage =
        new ChatUsage(
            generation.promptTokens(),
            generation.completionTokens(),
            generation.totalTokens(),
            generation.model() != null ? generation.model() : generationProperties.model());

    // One INFO line per request; answer content only at DEBUG.
    log.info(
        "chat conversationId={} retrievalMode={} retrieved={} contextChunks={} citations={} "
            + "latencyMs[retrieve={} assemble={} generate={}] "
            + "tokens[prompt={} completion={} total={}]",
        conversationId,
        context.retrievalMode(),
        context.retrievedCount(),
        context.offeredCitations().size(),
        citations.size(),
        millis(context.retrieveNanos()),
        millis(context.assembleNanos()),
        millis(generateNanos),
        usage.promptTokens(),
        usage.completionTokens(),
        usage.totalTokens());
    if (log.isDebugEnabled()) {
      log.debug("chat conversationId={} answer={}", conversationId, answer);
    }

    return new ChatResponse(conversationId, answer, citations, context.retrievalMode(), usage);
  }

  // Prepared pre-generation state carried from prepare() to finish(), so both the sync and
  // streaming
  // paths run the identical surrounding loop with only the generation call in between.
  private record RagContext(
      UUID existingConversationId,
      String question,
      String retrievalMode,
      int retrievedCount,
      List<Citation> offeredCitations,
      String systemPrompt,
      String userPrompt,
      long retrieveNanos,
      long assembleNanos) {}

  private List<QaPair> loadHistory(UUID conversationId) {
    int turns = generationProperties.historyTurns();
    if (turns == 0) {
      return List.of();
    }
    // Two messages per turn (user + assistant); PromptTemplate trims to the configured window.
    List<ChatMessage> recent = messageRepository.lastMessages(conversationId, turns * 2);
    List<QaPair> pairs = new ArrayList<>();
    String pendingQuestion = null;
    for (ChatMessage message : recent) {
      if (message.role() == MessageRole.USER) {
        pendingQuestion = message.content();
      } else if (message.role() == MessageRole.ASSISTANT && pendingQuestion != null) {
        pairs.add(new QaPair(pendingQuestion, message.content()));
        pendingQuestion = null;
      }
    }
    return pairs;
  }

  private List<RetrievedChunk> toRetrievedChunks(List<HybridSearchHit> hits) {
    if (hits.isEmpty()) {
      return List.of();
    }
    List<UUID> chunkIds = hits.stream().map(HybridSearchHit::chunkId).toList();
    Map<UUID, ChunkBody> bodies = chunkContentService.findBodies(chunkIds);
    List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
    for (HybridSearchHit hit : hits) {
      ChunkBody body = bodies.get(hit.chunkId());
      if (body == null) {
        // Chunk deleted between search and body fetch — skip it rather than render a null.
        continue;
      }
      chunks.add(new RetrievedChunk(hit.citation(), body.content(), body.tokenCount()));
    }
    return chunks;
  }

  private static int normalizeTopK(Integer requested) {
    if (requested == null) {
      return DEFAULT_TOP_K;
    }
    return Math.max(1, Math.min(requested, MAX_TOP_K));
  }

  private static long millis(long nanos) {
    return nanos / 1_000_000;
  }
}
