ALTER TABLE github_repository ADD COLUMN readme_excerpt TEXT;
CREATE INDEX idx_repo_topic_topic_repo ON repo_topic (topic_id, repo_id);
