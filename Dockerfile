FROM ruby:3.3.7

WORKDIR /site

COPY Gemfile Gemfile.lock ./
RUN bundle install

COPY . .

EXPOSE 4000 35729

CMD ["bundle", "exec", "jekyll", "serve", "--config", "_config.yml,_config.local.yml", "--host", "0.0.0.0", "--livereload"]
