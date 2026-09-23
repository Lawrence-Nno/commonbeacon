# frozen_string_literal: true
# Synthetic fixture only. Run in an EMPTY, DISPOSABLE Discourse development database.
raise "Fixture generation requires explicit isolated development mode" unless Rails.env.development? && ENV["CB_FIXTURE_ONLY"] == "yes"
raise "Existing community data found" if User.where("id > 0").exists?
RateLimiter.disable
SiteSetting.min_first_post_length = 10
SiteSetting.min_post_length = 1
alice = User.create!(username: "fixture_alice", email: "alice@example.invalid", name: "Fixture Alice", active: true, approved: true, trust_level: 4)
bob = User.create!(username: "fixture_bob", email: "bob@example.invalid", name: "Fixture Bob", active: true, approved: true, trust_level: 4)
category = Category.create!(name: "Migration fixtures", slug: "migration-fixtures", user: alice)
category.update_columns(description: "Public source discussions for adapter verification.")
child = Category.create!(name: "Fixture subcategory", slug: "fixture-subcategory", user: alice, parent_category_id: category.id)
child.update_columns(description: "A nested source category.")
first = PostCreator.create!(alice, title: "How does the fixture migration work?", raw: "Keep **this Markdown** and <b>literal HTML</b> as text.\n\n[File](https://example.invalid/fixture.pdf)", category: category.id, skip_validations: true)
reply = PostCreator.create!(bob, topic_id: first.topic_id, raw: "A visible reply with a source attachment reference.", skip_validations: true)
nested = PostCreator.create!(alice, topic_id: first.topic_id, raw: "This nested reply must remain hidden.", reply_to_post_number: reply.post_number, skip_validations: true)
nested.update_columns(hidden: true, hidden_reason_id: 1)
unlisted = PostCreator.create!(bob, title: "An unlisted source discussion", raw: "This topic must be hidden after migration.", category: child.id, skip_validations: true)
unlisted.topic.update_columns(visible: false, closed: true, archived: true)
ENV["CB_CATEGORY_IDS"] = category.id.to_s
ENV["CB_SOURCE_INSTANCE_ID"] = "5ce4206c-2b9b-4134-b2f0-d48fda1e5027"
load "/tmp/export_commonbeacon.rb"
