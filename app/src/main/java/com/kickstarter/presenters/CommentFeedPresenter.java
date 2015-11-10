package com.kickstarter.presenters;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import com.kickstarter.KSApplication;
import com.kickstarter.libs.CurrentUser;
import com.kickstarter.libs.Presenter;
import com.kickstarter.libs.rx.transformers.Transformers;
import com.kickstarter.models.Comment;
import com.kickstarter.models.Project;
import com.kickstarter.models.User;
import com.kickstarter.presenters.errors.CommentFeedPresenterErrors;
import com.kickstarter.presenters.inputs.CommentFeedPresenterInputs;
import com.kickstarter.presenters.outputs.CommentFeedPresenterOutputs;
import com.kickstarter.services.ApiClient;
import com.kickstarter.services.apiresponses.CommentsEnvelope;
import com.kickstarter.services.apiresponses.ErrorEnvelope;
import com.kickstarter.ui.activities.CommentFeedActivity;
import com.kickstarter.ui.viewholders.EmptyCommentFeedViewHolder;
import com.kickstarter.ui.viewholders.ProjectContextViewHolder;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.subjects.PublishSubject;

public final class CommentFeedPresenter extends Presenter<CommentFeedActivity> implements CommentFeedPresenterInputs, CommentFeedPresenterOutputs, CommentFeedPresenterErrors {
  // INPUTS
  private final PublishSubject<String> commentBody = PublishSubject.create();
  private final PublishSubject<Void> contextClick = PublishSubject.create();
  private final PublishSubject<Void> loginClick = PublishSubject.create();

  // OUTPUTS
  private final PublishSubject<Void> commentPosted = PublishSubject.create();
  public Observable<Void> commentPosted() { return commentPosted.asObservable(); }

  // ERRORS
  private final PublishSubject<ErrorEnvelope> postCommentError = PublishSubject.create();
  public Observable<String> postCommentError() {
    return postCommentError
      .map(ErrorEnvelope::errorMessage);
  }

  private final PublishSubject<Void> loginSuccess = PublishSubject.create();
  private final PublishSubject<String> bodyOnPostClick = PublishSubject.create();
  private final PublishSubject<Void> commentDialogShown = PublishSubject.create();
  private final PublishSubject<Boolean> commentIsPosting = PublishSubject.create();
  private final PublishSubject<Project> initialProject = PublishSubject.create();
  private final PublishSubject<Void> refreshFeed = PublishSubject.create();

  @Inject ApiClient client;
  @Inject CurrentUser currentUser;

  public final CommentFeedPresenterInputs inputs = this;
  public final CommentFeedPresenterOutputs outputs = this;
  public final CommentFeedPresenterErrors errors = this;

  @Override
  public void commentBody(@NonNull final String string) {
    commentBody.onNext(string);
  }

  @Override
  public void emptyCommentFeedLoginClicked(@NonNull final EmptyCommentFeedViewHolder viewHolder) {
    loginClick.onNext(null);
  }

  @Override
  public void projectContextClicked(@NonNull final ProjectContextViewHolder viewHolder) {
    contextClick.onNext(null);
  }

  @Override
  protected void onCreate(@NonNull final Context context, @Nullable final Bundle savedInstanceState) {
    super.onCreate(context, savedInstanceState);
    ((KSApplication) context.getApplicationContext()).component().inject(this);

    final Observable<Project> project = initialProject
      .compose(Transformers.takeWhen(loginSuccess))
      .mergeWith(initialProject)
      .flatMap(client::fetchProject)
      .share();

    final Observable<List<Comment>> comments = project
      .compose(Transformers.takeWhen(refreshFeed))
      .switchMap(this::comments)
      .map(CommentsEnvelope::comments)
      .share();

    final Observable<List<?>> viewCommentsProject = Observable.combineLatest(
      Arrays.asList(viewSubject, comments, project),
      Arrays::asList);

    final Observable<Pair<CommentFeedActivity, Project>> viewAndProject = viewSubject
      .compose(Transformers.combineLatestPair(project))
      .share();

    final Observable<Boolean> commentHasBody = commentBody
      .map(body -> body.length() > 0);

    final Observable<Comment> postedComment = project
      .compose(Transformers.takePairWhen(bodyOnPostClick))
      .switchMap(pb -> postComment(pb.first, pb.second))
      .share();

    addSubscription(postedComment
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(this::postCommentSuccess)
    );

    addSubscription(viewAndProject
        .compose(Transformers.takeWhen(loginSuccess))
        .filter(vp -> vp.second.isBacking())
        .take(1)
        .map(vp -> vp.first)
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(CommentFeedActivity::showCommentDialog)
    );

    addSubscription(currentUser.observable()
        .compose(Transformers.combineLatestPair(viewCommentsProject))
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(uvcp -> {
          final User u = uvcp.first;
          final CommentFeedActivity view = (CommentFeedActivity) uvcp.second.get(0);
          final List<Comment> cs = (List<Comment>) uvcp.second.get(1);
          final Project p = (Project) uvcp.second.get(2);
          view.show(p, cs, u);
        })
    );

    addSubscription(viewSubject
        .compose(Transformers.takeWhen(contextClick))
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(CommentFeedActivity::onBackPressed)
    );

    addSubscription(viewSubject
      .compose(Transformers.takeWhen(loginClick))
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(CommentFeedActivity::commentFeedLogin)
    );

    addSubscription(viewSubject
      .compose(Transformers.combineLatestPair(commentHasBody))
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(ve -> ve.first.enablePostButton(ve.second))
    );

    addSubscription(viewSubject
      .compose(Transformers.takeWhen(refreshFeed))
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(CommentFeedActivity::dismissCommentDialog)
    );

    addSubscription(viewSubject
      .compose(Transformers.takePairWhen(commentIsPosting))
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe(vp -> vp.first.disablePostButton(vp.second))
    );

    addSubscription(initialProject
      .compose(Transformers.takePairWhen(postedComment))
      .subscribe(cp -> koala.trackProjectCommentCreate(cp.first, cp.second))
    );
    addSubscription(initialProject.take(1).subscribe(koala::trackProjectCommentsView));
  }

  private Observable<Comment> postComment(@NonNull final Project project, @NonNull final String body) {
    return client.postProjectComment(project, body)
      .compose(Transformers.pipeApiErrorsTo(postCommentError))
      .doOnSubscribe(() -> commentIsPosting.onNext(true))
      .doOnCompleted(() -> commentPosted.onNext(null))
      .finallyDo(() -> commentIsPosting.onNext(false));
  }

  private Observable<CommentsEnvelope> comments(@NonNull final Project project) {
    return client.fetchProjectComments(project)
      .compose(Transformers.neverError());
  }

  // todo: add pagination to comments
  public void initialize(@NonNull final Project initialProject) {
    this.initialProject.onNext(initialProject);
    refreshFeed.onNext(null);
  }

  public void postClick(@NonNull final String body) {
    bodyOnPostClick.onNext(body);
  }

  public void takeCommentDialogShown() {
    commentDialogShown.onNext(null);
  }

  private void postCommentSuccess(@Nullable final Comment comment) {
    refreshFeed.onNext(null);
  }

  public void takeLoginSuccess() {
    loginSuccess.onNext(null);
    refreshFeed.onNext(null);
  }
}
