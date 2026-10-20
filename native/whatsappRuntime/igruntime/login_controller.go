package igruntime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/rs/zerolog"
	"go.mau.fi/util/exhttp"
	"maunium.net/go/mautrix/bridgev2"

	"go.mau.fi/mautrix-meta/pkg/instameow"
	"go.mau.fi/mautrix-meta/pkg/messagix/cookies"
	"go.mau.fi/mautrix-meta/pkg/messagix/types"
)

// Fixed, secret-free failure codes surfaced to the screen. Upstream error text
// (which can carry URLs or account detail) is never stored or returned.
const (
	LoginFailureCredentialsRejected = "credentials_rejected"
	LoginFailureCheckpoint          = "checkpoint_unsupported"
	LoginFailureRateLimited         = "rate_limited"
	LoginFailureAccountSuspended    = "account_suspended"
	LoginFailureIdentityMismatch    = "verified_identity_mismatch"
	LoginFailureNetwork             = "network_error"
	LoginFailureUpstream            = "upstream_error"
	LoginFailureStaleCallback       = "stale_callback"
	LoginFailureCancelled           = "cancelled"
)

// loginFieldUsername/Password match the pinned upstream credential field IDs.
const (
	loginFieldUsername = "username"
	loginFieldPassword = "password"
)

// LoginController drives the real pinned mautrix-meta native Instagram login
// against instameow.Client. It reproduces the pkg/igconnector MetaNativeLogin
// android flow using only exported instameow methods, so every request is a
// genuine upstream request. It deliberately does not import pkg/igconnector,
// which would pull whatsmeow (Messenger Armadillo E2EE) into this runtime.
//
// One controller drives one authentication attempt. Credentials are consumed
// transiently and never stored. The verified account identity comes from the
// upstream session and the authenticated inbox read, not from screen input.
type LoginController struct {
	mu        sync.Mutex
	client    *instameow.Client
	machine   *LoginMachine
	accountID string

	ctx    context.Context
	cancel context.CancelFunc

	// attempt increments whenever a new WebView handoff is issued. A WebView
	// result tagged with a stale attempt is rejected.
	attempt atomic.Int64

	// webViewFields records the exact field IDs the current cookies/CAPTCHA step
	// expects, so a result can only submit those keys.
	webViewFields []string

	device   *types.InstagramLoginDevice
	deviceMu sync.Mutex

	verifiedUserID   string
	verifiedUsername string
	verifiedSession  []byte
	finished         bool
}

// NewLoginController builds a fresh login client bound to one account slot. The
// account ID is a MINI-local key; the stored account is bound to the verified
// upstream user identity on success, not to this key or any screen input.
func NewLoginController(accountID string) *LoginController {
	accountID = strings.TrimSpace(accountID)
	ctx, cancel := context.WithCancel(context.Background())
	lc := &LoginController{
		machine:   NewLoginMachine(),
		accountID: accountID,
		ctx:       ctx,
		cancel:    cancel,
	}
	lc.client = lc.newLoginClient()
	return lc
}

func (lc *LoginController) newLoginClient() *instameow.Client {
	loginCookies := &cookies.Cookies{Platform: types.Instagram}
	loginCookies.UpdateValues(nil)
	return instameow.NewClient(instameow.ClientParams{
		Cookies:       loginCookies,
		Log:           zerolog.Nop(),
		Settings:      exhttp.SensibleClientSettings,
		DisableTyping: true,
		// LogRedactedLoginResponses is intentionally left false: no login
		// request/response, URL, or cookie is ever logged.
		MobileLoginDevice: lc.device,
		SaveMobileLoginDevice: func(_ context.Context, device types.InstagramLoginDevice) error {
			lc.deviceMu.Lock()
			d := device
			lc.device = &d
			lc.deviceMu.Unlock()
			return nil
		},
	})
}

// AccountID returns the MINI-local slot key for this attempt.
func (lc *LoginController) AccountID() string { return lc.accountID }

// AttemptID is the current WebView handoff generation. The dedicated WebView
// controller must echo it back so stale callbacks are rejected.
func (lc *LoginController) AttemptID() int64 { return lc.attempt.Load() }

// Snapshot is the secret-free JSON for ordinary UI state.
func (lc *LoginController) Snapshot() []byte { return lc.machine.SnapshotJSON() }

// State returns the current sanitized login state.
func (lc *LoginController) State() string { return lc.machine.State() }

// VerifiedUserID is the upstream-verified Instagram user ID. It is empty until
// the upstream session is established and the authenticated read succeeds.
func (lc *LoginController) VerifiedUserID() string {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	return lc.verifiedUserID
}

func (lc *LoginController) VerifiedUsername() string {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	return lc.verifiedUsername
}

// Start returns the initial credentials step snapshot. No network I/O occurs.
func (lc *LoginController) Start() []byte {
	return lc.machine.SnapshotJSON()
}

// SubmitCredentials begins the real upstream native login. The username and
// password are used only for the duration of the upstream call and are never
// stored, logged, or placed in any snapshot.
func (lc *LoginController) SubmitCredentials(username, password string) error {
	if strings.TrimSpace(username) == "" || password == "" {
		return errors.New("username and password are required")
	}
	if lc.machine.State() != LoginStateCredentials {
		return errors.New("credentials step is not active")
	}
	input := map[string]string{
		loginFieldUsername: username,
		loginFieldPassword: password,
	}
	return lc.runUpstream(input)
}

// SubmitInput continues a non-WebView upstream user-input step (for example a
// two-factor code or an account selection). Input is JSON of field ID to value.
func (lc *LoginController) SubmitInput(inputJSON []byte) error {
	switch lc.machine.State() {
	case LoginStateTwoFactor, LoginStateSelect, LoginStateDisplayWait:
	default:
		return errors.New("no interactive upstream step is active")
	}
	input, err := decodeInputMap(inputJSON)
	if err != nil {
		return err
	}
	return lc.runUpstream(input)
}

// WebViewRequestJSON returns the temporary WebView instructions for the current
// challenge/CAPTCHA step. It is for the dedicated in-process WebView controller
// only and must never be persisted or logged. It includes the attempt ID the
// result must echo back.
func (lc *LoginController) WebViewRequestJSON() []byte {
	if !lc.machine.WebViewPending() {
		return nil
	}
	request := map[string]any{
		"attempt":      lc.attempt.Load(),
		"url":          lc.machine.WebViewURL(),
		"user_agent":   lc.machine.WebViewUserAgent(),
		"extract_js":   lc.machine.WebViewExtractJS(),
		"wait_pattern": lc.machine.WebViewWaitPattern(),
		"fields":       append([]string(nil), lc.webViewFields...),
	}
	value, _ := json.Marshal(request)
	return value
}

// SubmitWebViewResult feeds a dedicated-WebView result back into the upstream
// step. The attempt ID must match the generation that issued the request;
// stale callbacks after cancellation or step advance are rejected. The result
// values (CAPTCHA/reCAPTCHA token or challenge cookies) are passed straight to
// the upstream process and never enter ordinary UI state.
func (lc *LoginController) SubmitWebViewResult(attempt int64, resultJSON []byte) error {
	if attempt != lc.attempt.Load() {
		return errors.New(LoginFailureStaleCallback)
	}
	if !lc.machine.WebViewPending() {
		return errors.New(LoginFailureStaleCallback)
	}
	result, err := decodeInputMap(resultJSON)
	if err != nil {
		return err
	}
	// Only the exact fields the step declared may be submitted.
	allowed := make(map[string]struct{}, len(lc.webViewFields))
	for _, field := range lc.webViewFields {
		allowed[field] = struct{}{}
	}
	input := make(map[string]string, len(result))
	for key, value := range result {
		if _, ok := allowed[key]; !ok {
			return fmt.Errorf("unexpected WebView result field %q", key)
		}
		input[key] = value
	}
	if len(input) == 0 {
		return errors.New("WebView result is empty")
	}
	return lc.runUpstream(input)
}

// runUpstream performs one real upstream native-login turn and maps the result
// into the sanitized machine. It bumps the attempt generation whenever it
// issues a new WebView handoff so prior handoffs cannot be answered.
func (lc *LoginController) runUpstream(input map[string]string) error {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	if lc.finished {
		return errors.New("login attempt already finished")
	}
	if lc.ctx.Err() != nil {
		lc.machine.markFailed(LoginFailureCancelled)
		return errors.New(LoginFailureCancelled)
	}

	step, err := lc.client.DoInstagramCAALoginSteps(lc.ctx, input)
	if err != nil {
		return lc.handleUpstreamError(err)
	}
	if step == nil {
		// Upstream reached a successful session with no further user step.
		return lc.complete()
	}
	return lc.applyStep(step)
}

// applyStep translates a pinned bridgev2.LoginStep into the sanitized machine.
func (lc *LoginController) applyStep(step *bridgev2.LoginStep) error {
	lc.webViewFields = nil
	switch step.Type {
	case bridgev2.LoginStepTypeComplete:
		return lc.complete()

	case bridgev2.LoginStepTypeUserInput:
		state := LoginStateTwoFactor
		fieldIDs := make([]string, 0)
		if step.UserInputParams != nil {
			for _, field := range step.UserInputParams.Fields {
				fieldIDs = append(fieldIDs, field.ID)
				if field.Type == bridgev2.LoginInputFieldTypeSelect {
					state = LoginStateSelect
				}
			}
		}
		return lc.machine.Apply(UpstreamStep{
			State:        state,
			StepID:       step.StepID,
			Instructions: step.Instructions,
			FieldIDs:     fieldIDs,
		})

	case bridgev2.LoginStepTypeCookies:
		if step.CookiesParams == nil {
			lc.machine.markFailed(LoginFailureUpstream)
			return errors.New(LoginFailureUpstream)
		}
		fieldIDs := make([]string, 0, len(step.CookiesParams.Fields))
		for _, field := range step.CookiesParams.Fields {
			fieldIDs = append(fieldIDs, field.ID)
		}
		lc.webViewFields = fieldIDs
		// A special-source single-token step is a CAPTCHA; a cookie-extraction
		// step is a web challenge. Both run in the dedicated WebView.
		state := LoginStateWebChallenge
		if isCaptchaCookieStep(step.CookiesParams) {
			state = LoginStateCaptcha
		}
		lc.attempt.Add(1)
		return lc.machine.Apply(UpstreamStep{
			State:              state,
			StepID:             step.StepID,
			Instructions:       step.Instructions,
			WebViewURL:         step.CookiesParams.URL,
			WebViewUserAgent:   step.CookiesParams.UserAgent,
			WebViewExtractJS:   step.CookiesParams.ExtractJS,
			WebViewWaitPattern: step.CookiesParams.WaitForURLPattern,
		})

	case bridgev2.LoginStepTypeDisplayAndWait:
		return lc.machine.Apply(UpstreamStep{
			State:        LoginStateDisplayWait,
			StepID:       step.StepID,
			Instructions: step.Instructions,
		})

	default:
		lc.machine.markFailed(LoginFailureCheckpoint)
		return errors.New(LoginFailureCheckpoint)
	}
}

func isCaptchaCookieStep(params *bridgev2.LoginCookiesParams) bool {
	if params.WaitForURLPattern != "" {
		return false
	}
	for _, field := range params.Fields {
		for _, source := range field.Sources {
			if source.Type == bridgev2.LoginCookieTypeSpecial {
				return true
			}
		}
	}
	return false
}

// complete runs the harmless authenticated inbox read, binds the stored account
// to the upstream-verified user identity, and exports the encrypted-session
// envelope. Only after this does the machine become authenticated.
func (lc *LoginController) complete() error {
	nativeSession := lc.client.GetInstagramNativeSession()
	if nativeSession == nil || nativeSession.Authorization == "" || nativeSession.UserID == "" {
		lc.machine.markFailed(LoginFailureUpstream)
		return errors.New(LoginFailureUpstream)
	}
	lc.client.SetInstagramNativeSession(nativeSession)

	// Harmless authenticated read: load the inbox index to prove the session is
	// real and obtain the upstream-verified viewer identity.
	viewer, _, err := lc.client.LoadIndex(lc.ctx)
	if err != nil {
		return lc.handleUpstreamError(err)
	}
	if viewer == nil || viewer.GetUserId() == "" {
		lc.machine.markFailed(LoginFailureUpstream)
		return errors.New(LoginFailureUpstream)
	}

	dsUserID := lc.client.GetCookies().Get(cookies.IGCookieDSUserID)
	// Bind to the upstream-verified identity: the native session, the inbox
	// viewer, and the session cookie must all agree.
	if nativeSession.UserID != viewer.GetUserId() || nativeSession.UserID != dsUserID {
		lc.machine.markFailed(LoginFailureIdentityMismatch)
		return errors.New(LoginFailureIdentityMismatch)
	}

	runtime := &Runtime{client: lc.client, accountID: lc.accountID}
	envelope, err := runtime.ExportSessionEnvelope(lc.accountID)
	if err != nil {
		lc.machine.markFailed(LoginFailureUpstream)
		return errors.New(LoginFailureUpstream)
	}

	lc.verifiedUserID = nativeSession.UserID
	lc.verifiedUsername = viewer.GetUsername()
	lc.verifiedSession = envelope
	lc.finished = true
	lc.webViewFields = nil
	return lc.machine.Apply(UpstreamStep{
		State:        LoginStateAuthenticated,
		StepID:       LoginStepCredentials,
		Instructions: "",
	})
}

// ExportVerifiedSession returns the encrypted-session envelope bytes to be
// handed directly to the Android SQLCipher/Keystore store. It is available only
// after authentication succeeds.
func (lc *LoginController) ExportVerifiedSession() ([]byte, error) {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	if len(lc.verifiedSession) == 0 {
		return nil, errors.New("no verified Instagram session is available")
	}
	return append([]byte(nil), lc.verifiedSession...), nil
}

// handleUpstreamError maps a real upstream error onto a fixed, secret-free
// failure code while preserving cancellation. The error text itself is dropped.
func (lc *LoginController) handleUpstreamError(err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(lc.ctx.Err(), context.Canceled) {
		lc.machine.markFailed(LoginFailureCancelled)
		return errors.New(LoginFailureCancelled)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		lc.machine.markFailed(LoginFailureNetwork)
		return errors.New(LoginFailureNetwork)
	}
	code := classifyUpstreamError(err)
	lc.machine.markFailed(code)
	return errors.New(code)
}

func classifyUpstreamError(err error) string {
	if err == nil {
		return LoginFailureUpstream
	}
	var respErr bridgev2.RespError
	if errors.As(err, &respErr) {
		switch {
		case strings.Contains(respErr.ErrCode, "RATE_LIMITED"):
			return LoginFailureRateLimited
		case strings.Contains(respErr.ErrCode, "ACCOUNT_SUSPENDED"),
			strings.Contains(respErr.ErrCode, "PENDING_DELETION"):
			return LoginFailureAccountSuspended
		case strings.Contains(respErr.ErrCode, "CAPTCHA"),
			strings.Contains(respErr.ErrCode, "CHECKPOINT"),
			strings.Contains(respErr.ErrCode, "CAA_STEP"),
			strings.Contains(respErr.ErrCode, "OAUTH"),
			strings.Contains(respErr.ErrCode, "PASSKEY"):
			return LoginFailureCheckpoint
		case strings.Contains(respErr.ErrCode, "MATRIX_ID"),
			strings.Contains(respErr.ErrCode, "CREDENTIALS"),
			strings.Contains(respErr.ErrCode, "PHONE_NUMBER"):
			return LoginFailureCredentialsRejected
		}
	}
	return LoginFailureUpstream
}

// Cancel aborts the attempt, requests upstream cancellation of the active step,
// clears upstream login state, and marks the machine cancelled. Any WebView
// result delivered after this is rejected as stale.
func (lc *LoginController) Cancel() {
	lc.attempt.Add(1)
	lc.cancel()
	lc.mu.Lock()
	defer lc.mu.Unlock()
	if lc.client != nil {
		// Best-effort upstream cancellation with a fresh context (lc.ctx is
		// already cancelled). Errors are ignored; state is cleared regardless.
		cancelCtx, cancel := context.WithCancel(context.Background())
		_ = lc.client.CancelInstagramCAALoginStep(cancelCtx)
		cancel()
		lc.client.ClearInstagramCAALoginState()
	}
	lc.verifiedSession = nil
	lc.verifiedUserID = ""
	lc.verifiedUsername = ""
	lc.finished = true
	lc.webViewFields = nil
	lc.machine.Cancel()
}

// Close releases upstream login state after the attempt is fully handled. It is
// safe to call multiple times. It never affects any other login flow.
func (lc *LoginController) Close() {
	lc.mu.Lock()
	defer lc.mu.Unlock()
	if lc.client != nil {
		lc.client.ClearInstagramCAALoginState()
	}
	lc.verifiedSession = nil
}

func decodeInputMap(data []byte) (map[string]string, error) {
	if len(data) == 0 {
		return map[string]string{}, nil
	}
	var input map[string]string
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, errors.New("invalid input payload")
	}
	if input == nil {
		input = map[string]string{}
	}
	return input, nil
}
