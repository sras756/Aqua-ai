const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date();
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied on all user collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/conversations`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/memories`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/skills`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/wallets`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/settings`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/browser_tabs`).get());
});

test("Cross-user isolation: Bob cannot read or write Alice's data", async () => {
  const ts = nowTs();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(`users/${ALICE_UID}/memories/mem_1`).set({
      userId: ALICE_UID,
      category: "preference",
      title: "Timezone",
      content: "Prefer 24h clock",
      approvedByUser: true,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.doc(`users/${ALICE_UID}/memories/mem_1`).get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/memories`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/memories/mem_2`).set({
      userId: BOB_UID,
      category: "preference",
      title: "Spoof",
      content: "Attempted write",
      approvedByUser: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Ghost field & schema poisoning rejected on create", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = nowTs();
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/memories/mem_bad`).set({
      userId: ALICE_UID,
      category: "preference",
      title: "Valid",
      content: "Valid body",
      approvedByUser: true,
      isAdmin: true, // Ghost field
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Owner can create, list, update, and delete settings, conversations, messages, memories, skills, wallets, browser_tabs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = nowTs();

  // 1. Settings
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/settings/preferences`).set({
      userId: ALICE_UID,
      preferredModel: "gemini-4-hydrogen",
      thinkingEnabled: true,
      searchGroundingEnabled: true,
      mapsGroundingEnabled: false,
      wakeWordEnabled: false,
      wakeWordPhrase: "Hey Aqua",
      voiceLanguage: "en-US",
      voiceSpeed: 1.0,
      voicePitch: 1.0,
      useGeminiTts: true,
      autonomousReversibleActions: true,
      darkThemeMode: "aqua_abyss",
      useCustomDirectApiKey: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 2. Conversation & Message
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/conversations/conv_1`).set({
      userId: ALICE_UID,
      title: "Mission Brief",
      summary: "Started session",
      modelUsed: "gemini-4-hydrogen",
      messageCount: 1,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/conversations/conv_1/messages/msg_1`).set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "user",
      content: "Check my battery",
      modelUsed: "gemini-4-hydrogen",
      attachmentName: "",
      attachmentMime: "",
      toolActionsJson: "",
      groundingSourcesJson: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 3. Skill
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/skills/skill_1`).set({
      userId: ALICE_UID,
      name: "Market Watcher",
      description: "Monitors spot rates",
      version: "1.0.0",
      format: "json",
      instructions: "Use public price endpoints.",
      requiredPermissions: ["INTERNET"],
      tools: ["fetch_crypto_price"],
      configJson: "{}",
      securityStatus: "verified",
      securityReport: "No unsafe patterns found.",
      enabled: true,
      sourceLabel: "user-created",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 4. Wallet Config
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/wallets/wallet_1`).set({
      userId: ALICE_UID,
      walletProductName: "Flash Wallet",
      developerName: "Flash Labs",
      packageName: "com.flash.wallet",
      integrationMethod: "read_only_rpc",
      networkName: "Ethereum Mainnet",
      rpcEndpoint: "https://ethereum-rpc.publicnode.com",
      publicAddress: "0x0000000000000000000000000000000000000000",
      connectionStatus: "read_only",
      docsUrl: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 5. Browser Tab
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/browser_tabs/tab_1`).set({
      userId: ALICE_UID,
      title: "DuckDuckGo",
      url: "https://duckduckgo.com",
      lastSummary: "",
      isActive: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // List queries succeed
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/conversations`).get());
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/skills`).get());

  // Update skill enabled flag succeeds, immutable createdAt violation fails
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/skills/skill_1`).update({
      enabled: false,
      updatedAt: nowTs(),
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/skills/skill_1`).update({
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Delete succeeds
  await assertSucceeds(aliceDb.doc(`users/${ALICE_UID}/browser_tabs/tab_1`).delete());
});
