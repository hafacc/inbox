<script lang="ts">
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";
  import { AuthState, setAuthState } from "../lib/auth.svelte";
  import MailApp from "../lib/components/mail-app.svelte";
  import SignIn from "../lib/components/sign-in.svelte";

  const auth = setAuthState(new AuthState());
</script>

<svelte:head>
  <title>Inbox</title>
</svelte:head>

{#if auth.status === "signedOut"}
  <SignIn />
{:else if auth.status === "signedIn" && auth.user}
  {#key auth.user.uid}
    <svelte:boundary
      onerror={(error) => console.error("the app stopped", error)}
    >
      <MailApp uid={auth.user.uid} />
      {#snippet failed()}
        <div
          class="fixed inset-0 flex flex-col items-center justify-center gap-4 px-6 text-center"
          role="alert"
        >
          <p class="text-[15px]">Something went wrong.</p>
          <button
            type="button"
            onclick={() => location.reload()}
            class="h-10 px-5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:bg-accent-strong transition-colors"
          >
            Reload
          </button>
        </div>
      {/snippet}
    </svelte:boundary>
  {/key}
{:else}
  <div
    class="fixed inset-0 flex items-center justify-center animate-appear-late"
    role="status"
    aria-label="Loading"
  >
    <LoaderCircle class="w-7 h-7 text-muted animate-spin" aria-hidden="true" />
  </div>
{/if}
